package com.tinyme.agent.service;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.agent.model.AgentResourceIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(AgentDatabaseConfiguration.class)
class AgentResourcesTests {
    @Autowired
    AgentResources resources;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void savedResources() {
        jdbc.update("DELETE FROM settings WHERE key IN ('env.default', 'agent.chat', 'memory.main')");
        save("env.default", "{\"id\":\"env_saved\",\"seedHash\":\"environment-hash\"}");
        save("agent.chat", "{\"id\":\"agent_saved\",\"version\":3,\"seedHash\":\"agent-hash\"}");
        save("memory.main", "{\"id\":\"memstore_saved\"}");
    }

    @Test
    void readsIdsAndVersionFromProvisionerRecordShape() {
        assertThat(resources.load()).isEqualTo(new AgentResourceIds("env_saved", "agent_saved", 3, "memstore_saved"));
    }

    @Test
    void nextLoadReadsUpdatedAgentInsteadOfCachingIds() {
        resources.load();
        save("agent.chat", "{\"id\":\"agent_replaced\",\"version\":4}");
        assertThat(resources.load()).isEqualTo(new AgentResourceIds("env_saved", "agent_replaced", 4, "memstore_saved"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"env.default", "agent.chat", "memory.main"})
    void missingSettingNamesTheKeyAndRecoveryStep(String key) {
        jdbc.update("DELETE FROM settings WHERE key = ?", key);
        assertThatThrownBy(resources::load).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(key).hasMessageContaining("TINYME_AGENT_SETUP_ENABLED=true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"id\":\"\"}", "{\"id\":42}", "{\"id\":\"bad/id\"}", "[]", "null"})
    void rejectsMalformedSavedIds(String value) {
        save("env.default", value);
        assertThatThrownBy(resources::load).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("env.default").hasMessageContaining("Run agent setup");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "1.5", "\"3\"", "2147483648"})
    void rejectsMissingOrInvalidAgentVersion(String versionJson) {
        save("agent.chat", "{\"id\":\"agent_saved\",\"version\":" + versionJson + "}");
        assertThatThrownBy(resources::load).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent.chat").hasMessageContaining("version");
    }

    @Test
    void acceptsLegacyEnvironmentAndMemoryIds() {
        save("env.default", "\"env_saved\"");
        save("memory.main", "\"memstore_saved\"");
        assertThat(resources.load()).isEqualTo(new AgentResourceIds("env_saved", "agent_saved", 3, "memstore_saved"));
    }

    @Test
    void legacyAgentIdRequiresProvisioningToRecoverVersion() {
        save("agent.chat", "\"agent_saved\"");
        assertThatThrownBy(resources::load).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent.chat").hasMessageContaining("version").hasMessageContaining("Run agent setup");
    }

    private void save(String key, String json) {
        jdbc.update("""
                INSERT INTO settings (key, value) VALUES (?, ?::jsonb)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, json);
    }
}
