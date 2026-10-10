package com.tinyme.agent.bootstrap;

import com.tinyme.agent.client.ManagedAgentApi;
import com.tinyme.agent.support.AgentDatabaseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(AgentDatabaseConfiguration.class)
class AgentProvisionerTests {
    @Autowired DataSource datasource;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearSettings() {
        jdbc.update("DELETE FROM settings WHERE key IN ('env.default', 'agent.chat', 'memory.main')");
    }

    @Test
    void restartReusesIdsAndPreservesMemory() throws Exception {
        var api = mock(ManagedAgentApi.class);
        var created = new AtomicInteger();
        var seeded = new AtomicInteger();
        when(api.request(anyString(), anyString(), any(), anyBoolean())).thenAnswer(call -> {
            String method = call.getArgument(0);
            String path = call.getArgument(1);
            if (path.endsWith("/memories")) {
                assertThat((boolean) call.getArgument(3)).isTrue();
                Map<?, ?> payload = call.getArgument(2);
                assertThat(payload.get("path").toString()).startsWith("/me/");
                if (seeded.incrementAndGet() > 4) {
                    throw new ManagedAgentApi.ApiException(409, "memory_path_conflict_error");
                }
                return Map.of("id", "mem_test");
            }
            if (method.equals("POST")) return Map.of("id", "resource_" + created.incrementAndGet(), "version", 1);
            return Map.of("id", path.substring(path.lastIndexOf('/') + 1), "version", 1);
        });
        var provisioner = new AgentProvisioner(api);
        Map<String, String> first;
        try (var connection = datasource.getConnection()) {
            first = provisioner.provision(connection, Map.of());
        }
        try (var connection = datasource.getConnection()) {
            assertThat(provisioner.provision(connection, first)).isEqualTo(first);
        }
        assertThat(created.get()).isEqualTo(3);
        assertThat(seeded.get()).isEqualTo(8);
    }

    @Test
    void partialFailureKeepsCreatedIdForRecovery() throws Exception {
        var api = mock(ManagedAgentApi.class);
        when(api.request(eq("POST"), eq("/v1/environments"), any(), eq(false)))
                .thenAnswer(call -> Map.of("id", "env_saved"));
        when(api.request(eq("POST"), eq("/v1/agents"), any(), eq(false)))
                .thenThrow(new IOException("simulated network failure"));
        try (var connection = datasource.getConnection()) {
            assertThatThrownBy(() -> new AgentProvisioner(api).provision(connection, Map.of()))
                    .isInstanceOf(IOException.class);
        }
        assertThat(jdbc.queryForObject("SELECT value ->> 'id' FROM settings WHERE key = 'env.default'", String.class))
                .isEqualTo("env_saved");
        try (var connection = datasource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT pg_try_advisory_lock(724198032)")) {
            result.next();
            assertThat(result.getBoolean(1)).isTrue();
            statement.execute("SELECT pg_advisory_unlock(724198032)");
        }
    }

    private ManagedAgentApi api() throws Exception {
        var api = mock(ManagedAgentApi.class);
        when(api.request(anyString(), anyString(), any(), anyBoolean())).thenAnswer(call -> {
            String path = call.getArgument(1);
            if (path.endsWith("/memories")) return Map.of("id", "mem_seed");
            String id = switch (path) {
                case "/v1/agents" -> "agent_saved";
                case "/v1/environments" -> "env_saved";
                case "/v1/memory_stores" -> "memstore_saved";
                default -> path.substring(path.lastIndexOf('/') + 1);
            };
            return Map.of("id", id, "version", 3);
        });
        return api;
    }

    private Map<String, String> run(ManagedAgentApi api, boolean allow, Map<String, String> configured) throws Exception {
        try (var connection = datasource.getConnection()) {
            return new AgentProvisioner(api, allow).provision(connection, configured);
        }
    }

    @Test
    void changedAgentSeedUpdatesWithLiveVersion() throws Exception {
        var api = api();
        run(api, false, Map.of());
        jdbc.update("UPDATE settings SET value = jsonb_set(value, '{seedHash}', '\"old\"') WHERE key = 'agent.chat'");
        clearInvocations(api);
        when(api.request(eq("POST"), eq("/v1/agents/agent_saved"), any(), eq(false)))
                .thenAnswer(call -> {
                    Map<?, ?> payload = call.getArgument(2);
                    assertThat(payload.get("version")).isEqualTo(3);
                    assertThat(payload.get("system")).isNotNull();
                    return Map.of("id", "agent_saved", "version", 4);
                });
        run(api, false, Map.of());
        verify(api).request(eq("POST"), eq("/v1/agents/agent_saved"), any(), eq(false));
        assertThat(jdbc.queryForObject("SELECT (value ->> 'version')::int FROM settings WHERE key = 'agent.chat'", Integer.class))
                .isEqualTo(4);
        clearInvocations(api);
        run(api, false, Map.of());
        verify(api, never()).request(eq("POST"), eq("/v1/agents/agent_saved"), any(), anyBoolean());
    }

    @Test
    void changedEnvironmentSeedUpdatesWithoutVersion() throws Exception {
        var api = api();
        run(api, false, Map.of());
        jdbc.update("UPDATE settings SET value = jsonb_set(value, '{seedHash}', '\"old\"') WHERE key = 'env.default'");
        when(api.request(eq("POST"), eq("/v1/environments/env_saved"), any(), eq(false)))
                .thenAnswer(call -> {
                    Map<?, ?> payload = call.getArgument(2);
                    assertThat(payload.containsKey("version")).isFalse();
                    return Map.of("id", "env_saved");
                });
        run(api, false, Map.of());
        verify(api).request(eq("POST"), eq("/v1/environments/env_saved"), any(), eq(false));
    }

    @Test
    void agent404IsRecreatedAndStaleConfiguredIdIsIgnored() throws Exception {
        var api = api();
        run(api, false, Map.of());
        when(api.request(eq("GET"), eq("/v1/agents/agent_saved"), isNull(), eq(false)))
                .thenThrow(new ManagedAgentApi.ApiException(404, "not_found_error"));
        when(api.request(eq("POST"), eq("/v1/agents"), any(), eq(false)))
                .thenAnswer(call -> Map.of("id", "agent_replacement", "version", 1));
        assertThat(run(api, false, Map.of()).get("ANTHROPIC_AGENT_ID")).isEqualTo("agent_replacement");
        assertThat(run(api, false, Map.of("ANTHROPIC_AGENT_ID", "agent_saved"))
                .get("ANTHROPIC_AGENT_ID")).isEqualTo("agent_replacement");
    }

    @Test
    void archivedEnvironmentIsRecreated() throws Exception {
        var api = api();
        run(api, false, Map.of());
        when(api.request(eq("GET"), eq("/v1/environments/env_saved"), isNull(), eq(false)))
                .thenAnswer(call -> Map.of("id", "env_saved", "archived_at", "2026-10-04"));
        when(api.request(eq("POST"), eq("/v1/environments"), any(), eq(false)))
                .thenAnswer(call -> Map.of("id", "env_replacement"));
        assertThat(run(api, false, Map.of()).get("ANTHROPIC_ENVIRONMENT_ID")).isEqualTo("env_replacement");
    }

    @Test
    void memory404RequiresExplicitRecoveryPermission() throws Exception {
        var api = api();
        run(api, false, Map.of());
        when(api.request(eq("GET"), eq("/v1/memory_stores/memstore_saved"), isNull(), eq(true)))
                .thenThrow(new ManagedAgentApi.ApiException(404, "not_found_error"));
        clearInvocations(api);
        assertThatThrownBy(() -> run(api, false, Map.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Memory store not found").hasMessageContaining("TINYME_ALLOW_NEW_MEMORY_STORE=true");
        verify(api, never()).request(eq("POST"), eq("/v1/memory_stores"), any(), eq(true));
        when(api.request(eq("POST"), eq("/v1/memory_stores"), any(), eq(true)))
                .thenAnswer(call -> Map.of("id", "memstore_replacement"));
        assertThat(run(api, true, Map.of()).get("ANTHROPIC_MEMORY_STORE_ID")).isEqualTo("memstore_replacement");
    }

    @Test
    void legacyStringIdsAreAdoptedWithoutDuplicateCreation() throws Exception {
        var api = api();
        run(api, false, Map.of());
        jdbc.update("UPDATE settings SET value = to_jsonb(value ->> 'id')");
        clearInvocations(api);
        run(api, false, Map.of());
        verify(api, never()).request(eq("POST"), eq("/v1/agents"), any(), anyBoolean());
        verify(api, never()).request(eq("POST"), eq("/v1/environments"), any(), anyBoolean());
        verify(api, never()).request(eq("POST"), eq("/v1/memory_stores"), any(), anyBoolean());
        assertThat(jdbc.queryForObject("SELECT value ->> 'id' FROM settings WHERE key = 'agent.chat'", String.class))
                .isEqualTo("agent_saved");
    }

    @Test
    void canonicalHashIgnoresNestedMapKeyOrder() throws Exception {
        var first = new java.util.LinkedHashMap<String, Object>();
        first.put("name", "test");
        first.put("config", new java.util.LinkedHashMap<>(Map.of("b", 2, "a", 1)));
        var second = new java.util.LinkedHashMap<String, Object>();
        second.put("config", new java.util.TreeMap<>(Map.of("a", 1, "b", 2)));
        second.put("name", "test");
        assertThat(AgentProvisioner.seedHash(first)).isEqualTo(AgentProvisioner.seedHash(second));
        second.put("name", "changed");
        assertThat(AgentProvisioner.seedHash(first)).isNotEqualTo(AgentProvisioner.seedHash(second));
    }

}
