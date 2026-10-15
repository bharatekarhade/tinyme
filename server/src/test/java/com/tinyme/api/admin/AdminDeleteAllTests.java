package com.tinyme.api.admin;

import com.tinyme.agent.client.ManagedAgents;
import com.tinyme.agent.client.MemoryPage;
import com.tinyme.agent.client.MemoryRef;
import com.tinyme.agent.client.FakeManagedAgents;
import com.tinyme.agent.service.SessionTurnLock;
import com.tinyme.auth.service.DeviceTokenService;
import com.tinyme.maintenance.MaintenanceGate;
import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.domain.admin.UserDataTables;
import com.tinyme.integrations.google.service.GoogleConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@AutoConfigureMockMvc
@Import(AgentDatabaseConfiguration.class)
class AdminDeleteAllTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceTokenService tokens;
    @Autowired SessionTurnLock turnLocks;
    @Autowired MaintenanceGate gate;
    @Autowired com.tinyme.domain.health.repository.HealthSyncEpochRepository healthSyncEpoch;
    @MockitoBean ManagedAgents agents;
    @MockitoBean GoogleConnection googleConnection;
    private final AtomicInteger ips = new AtomicInteger(1);
    private String retainedToken;

    @BeforeEach
    void resetDatabaseAndStubs() {
        jdbc.execute("TRUNCATE " + String.join(", ", UserDataTables.ALL) + " RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM settings WHERE key IN ('env.default', 'agent.chat', 'memory.main', 'user.tz')");
        setting("env.default", "{\"id\":\"env_saved\"}");
        setting("agent.chat", "{\"id\":\"agent_saved\",\"version\":3}");
        setting("memory.main", "{\"id\":\"memstore_saved\"}");
        setting("user.tz", "\"Asia/Tokyo\"");
        gate.endMaintenance();
        reset(agents);
    }

    @Test
    void deletesRemoteAndLocalDataButKeepsAccountTokenSettingsAndReseedsJournal() throws Exception {
        seedRows();
        long epochBefore = healthSyncEpoch.current();
        when(agents.listMemories("memstore_saved", "/", null))
                .thenReturn(new MemoryPage(List.of(new MemoryRef("mem_old", "/people/kenji.md")), null));

        var request = deleteRequest("DELETE EVERYTHING", "test-only-owner-password");
        long tokenCountBefore = count("refresh_tokens");
        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").value(1))
                .andExpect(jsonPath("$.people").value(1))
                .andExpect(jsonPath("$.cards").value(0))
                .andExpect(jsonPath("$.messages").value(1))
                .andExpect(jsonPath("$.sessions").value(1))
                .andExpect(jsonPath("$.memory_documents").value(1));

        assertThat(count("entries")).isZero();
        assertThat(count("people")).isZero();
        assertThat(count("messages")).isZero();
        assertThat(count("agent_sessions")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cards WHERE slug = 'journal' AND system", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entry_kinds WHERE kind IN ('steps', 'sleep', 'hrv')",
                Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Long.class)).isEqualTo(1);
        assertThat(count("refresh_tokens")).isEqualTo(tokenCountBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settings", Long.class)).isEqualTo(6);
        assertThat(healthSyncEpoch.current()).isEqualTo(epochBefore + 1);
        mvc.perform(get("/people").header("Authorization", "Bearer " + retainedToken))
                .andExpect(status().isOk());
        verify(agents).deleteSession("sesn_delete_test");
        verify(googleConnection).disconnect();
        verify(agents).deleteMemory("memstore_saved", "mem_old");
        for (var seed : new com.tinyme.agent.bootstrap.MemorySeedFiles().load().entrySet()) {
            verify(agents).createMemory("memstore_saved", seed.getKey(), seed.getValue());
        }
        try (var permit = gate.enterTurn()) {
            assertThat(permit).isNotNull();
        }
    }

    @Test
    void failedGoogleRevocationDoesNotBlockDeleteAll() throws Exception {
        seedRows();
        when(agents.listMemories("memstore_saved", "/", null)).thenReturn(new MemoryPage(List.of(), null));
        org.mockito.Mockito.doThrow(new IOException("Google is unreachable"))
                .when(googleConnection).disconnect();

        mvc.perform(deleteRequest("DELETE EVERYTHING", "test-only-owner-password"))
                .andExpect(status().isOk());

        assertThat(count("entries")).isZero();
        verify(googleConnection).disconnect();
    }

    @Test
    void wrongPasswordAndConfirmationLeaveEverythingUntouched() throws Exception {
        seedRows();
        mvc.perform(deleteRequest("DELETE EVERYTHING", "wrong-password"))
                .andExpect(status().isUnauthorized());
        mvc.perform(deleteRequest("delete everything", "test-only-owner-password"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/delete-all").with(request -> {
                    request.setRemoteAddr("192.0.2." + ips.getAndIncrement());
                    return request;
                }).header("Authorization", "Bearer " + token()).contentType("application/json")
                        .content("{\"password\":\"test-only-owner-password\"}"))
                .andExpect(status().isBadRequest());
        assertThat(count("entries")).isEqualTo(1);
        assertThat(count("agent_sessions")).isEqualTo(1);
        verifyNoInteractions(agents);
    }

    @Test
    void memoryFailureReturns502AndLeavesDatabaseForSafeRetry() throws Exception {
        seedRows();
        when(agents.listMemories("memstore_saved", "/", null)).thenThrow(new IOException("remote down"));
        mvc.perform(deleteRequest("DELETE EVERYTHING", "test-only-owner-password"))
                .andExpect(status().isBadGateway());
        assertThat(count("entries")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM agent_sessions WHERE anthropic_session_id='sesn_delete_test'",
                String.class)).isEqualTo("deleted");

        var stream = FakeManagedAgents.fromResource(getClass(), "/streams/no-tool-reply.json").build();
        when(agents.createSession(anyMap())).thenReturn("sesn_after_delete");
        when(agents.openStream("sesn_after_delete")).thenAnswer(invocation -> stream.openStream("sesn_after_delete"));
        var chatResponse = mvc.perform(post("/conversations/today/messages")
                        .header("Authorization", "Bearer " + token())
                        .contentType("application/json")
                        .content("{\"client_msg_id\":\"" + UUID.randomUUID() + "\",\"text\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(chatResponse)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM agent_sessions WHERE anthropic_session_id='sesn_after_delete'",
                String.class)).isEqualTo("active");
        assertThat(count("agent_sessions")).isEqualTo(2);

        reset(agents);
        when(agents.listMemories("memstore_saved", "/", null)).thenReturn(new MemoryPage(List.of(), null));
        mvc.perform(deleteRequest("DELETE EVERYTHING", "test-only-owner-password"))
                .andExpect(status().isOk());
        assertThat(count("entries")).isZero();
    }

    @Test
    void runningTurnReturns409AndMaintenanceGateRejectsNewChatWith503() throws Exception {
        UUID sessionId = UUID.randomUUID();
        assertThat(turnLocks.acquire(sessionId, Duration.ZERO)).isTrue();
        try {
            mvc.perform(deleteRequest("DELETE EVERYTHING", "test-only-owner-password"))
                    .andExpect(status().isConflict());
        } finally {
            turnLocks.release(sessionId);
        }

        assertThat(gate.beginMaintenance(false)).isTrue();
        try {
            mvc.perform(post("/conversations/today/messages")
                            .header("Authorization", "Bearer " + token())
                            .contentType("application/json")
                            .content("{\"client_msg_id\":\"" + UUID.randomUUID()
                                    + "\",\"text\":\"hello\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("TinyMe is resetting, try again in a minute"));
        } finally {
            gate.endMaintenance();
        }
    }

    @Test
    void endpointRequiresAuthentication() throws Exception {
        mvc.perform(post("/admin/delete-all").contentType("application/json")
                        .content("{\"confirm\":\"DELETE EVERYTHING\",\"password\":\"anything\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everyApplicationTableIsEitherUserDataOrExplicitlyKept() {
        var expected = new java.util.HashSet<>(UserDataTables.ALL);
        expected.addAll(UserDataTables.KEEP);
        var actual = new java.util.HashSet<>(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                """, String.class));
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder deleteRequest(
            String confirm, String password) {
        String body = "{\"confirm\":\"" + confirm + "\",\"password\":\"" + password + "\"}";
        return post("/admin/delete-all").with(request -> {
            request.setRemoteAddr("192.0.2." + ips.getAndIncrement());
            return request;
        }).header("Authorization", "Bearer " + token()).contentType("application/json").content(body);
    }

    private String token() { return tokens.issue("delete-all-test").token(); }

    private void seedRows() {
        jdbc.update("INSERT INTO entry_kinds (kind, description) VALUES ('drink', 'Drinks')");
        jdbc.update("INSERT INTO entries (ts, local_day, kind, quantity, data) VALUES (now(), current_date, 'drink', 1, '{}')");
        jdbc.update("INSERT INTO people (slug, display_name, memory_path) VALUES ('kenji', 'Kenji', 'people/kenji.md')");
        UUID session = jdbc.queryForObject("""
                INSERT INTO agent_sessions (anthropic_session_id, agent, agent_version, kind, local_day, status)
                VALUES ('sesn_delete_test', 'chat', 3, 'chat', current_date, 'active') RETURNING id
                """, UUID.class);
        jdbc.update("INSERT INTO messages (session_id, role, content) VALUES (?, 'USER', 'hello')", session);
        setting("delete-test", "true");
        retainedToken = tokens.issue("kept-token").token();
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private void setting(String key, String value) {
        jdbc.update("INSERT INTO settings (key, value) VALUES (?, CAST(? AS jsonb)) ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value",
                key, value);
    }
}
