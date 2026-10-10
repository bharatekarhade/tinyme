package com.tinyme.agent.service;

import com.tinyme.agent.client.ManagedAgentApi;
import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.agent.model.SessionRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false"
})
@Import({AgentDatabaseConfiguration.class, SessionManagerTests.TimeConfiguration.class})
class SessionManagerTests {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    @Autowired
    SessionManager manager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @MockitoBean
    ManagedAgentApi api;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM agent_sessions");
        jdbc.update("DELETE FROM settings WHERE key IN ('env.default', 'agent.chat', 'memory.main')");
        jdbc.update("""
                INSERT INTO settings (key, value) VALUES
                ('env.default', '{"id":"env_saved"}'),
                ('agent.chat', '{"id":"agent_saved","version":3}'),
                ('memory.main', '{"id":"memstore_saved"}')
                """);
    }

    @Test
    void createsPinnedSessionWithMemoryAndPersistsBothIds() throws Exception {
        when(api.createSession(anyMap())).thenReturn("sesn_created");

        SessionRef result = manager.todaySession(TOKYO);

        assertThat(result.sessionRowId()).isNotNull();
        assertThat(result.sessionRowId().version()).isEqualTo(7);
        assertThat(result.anthropicSessionId()).isEqualTo("sesn_created");
        var row = jdbc.queryForMap("""
                SELECT anthropic_session_id, agent, agent_version, kind, local_day::text, status, created_at
                FROM agent_sessions WHERE id = ?
                """, result.sessionRowId());
        assertThat(row).containsEntry("anthropic_session_id", "sesn_created")
                .containsEntry("agent", "chat").containsEntry("agent_version", 3)
                .containsEntry("kind", "chat").containsEntry("local_day", TODAY.toString())
                .containsEntry("status", "active");
        assertThat(row.get("created_at")).isNotNull();

        ArgumentCaptor<Map<?, ?>> body = ArgumentCaptor.captor();
        verify(api).createSession(body.capture());
        assertThat(body.getValue().get("agent")).isEqualTo(Map.of("type", "agent", "id", "agent_saved", "version", 3));
        assertThat(body.getValue().get("environment_id")).isEqualTo("env_saved");
        List<?> resources = (List<?>) body.getValue().get("resources");
        assertThat(resources).hasSize(1);
        Map<?, ?> memory = (Map<?, ?>) resources.getFirst();
        assertThat(memory.get("type")).isEqualTo("memory_store");
        assertThat(memory.get("memory_store_id")).isEqualTo("memstore_saved");
        assertThat(memory.get("access")).isEqualTo("read_write");
        assertThat((String) memory.get("instructions")).contains("me/profile.md", "mount_path");

        assertThat(manager.todaySession(TOKYO)).isEqualTo(result);
        verifyNoMoreInteractions(api);
    }

    @Test
    void reusesExistingSessionWithoutLoadingResourcesOrCallingApi() throws Exception {
        UUID rowId = seed("sesn_existing", "chat", "active", TODAY);
        jdbc.update("DELETE FROM settings WHERE key IN ('env.default', 'agent.chat', 'memory.main')");
        assertThat(manager.todaySession(TOKYO)).isEqualTo(new SessionRef(rowId, "sesn_existing"));
        verifyNoInteractions(api);
    }

    @Test
    void ignoresEndedDeletedJobAndPreviousDaySessions() throws Exception {
        seed("sesn_ended", "chat", "ended", TODAY);
        seed("sesn_deleted", "chat", "deleted", TODAY);
        seed("sesn_job", "job", "active", TODAY);
        seed("sesn_yesterday", "chat", "active", TODAY.minusDays(1));
        when(api.createSession(anyMap())).thenReturn("sesn_new");
        assertThat(manager.todaySession(TOKYO).anthropicSessionId()).isEqualTo("sesn_new");
        assertThat(activeChatsToday()).isEqualTo(1);
    }

    @Test
    void todayUsesTheRequestedZoneRatherThanUtc() throws Exception {
        when(api.createSession(anyMap())).thenReturn("sesn_tokyo", "sesn_utc");
        SessionRef tokyo = manager.todaySession(TOKYO);
        SessionRef utc = manager.todaySession(ZoneOffset.UTC);
        assertThat(tokyo).isNotEqualTo(utc);
        assertThat(jdbc.queryForObject("SELECT local_day::text FROM agent_sessions WHERE id = ?",
                String.class, tokyo.sessionRowId())).isEqualTo("2026-10-06");
        assertThat(jdbc.queryForObject("SELECT local_day::text FROM agent_sessions WHERE id = ?",
                String.class, utc.sessionRowId())).isEqualTo("2026-10-05");
    }

    @Test
    void concurrentCallsUseTheWinningRowWithoutPoisoningCallerTransactions() throws Exception {
        var bothCreating = new CyclicBarrier(2);
        var nextId = new AtomicInteger();
        when(api.createSession(anyMap())).thenAnswer(call -> {
            int id = nextId.incrementAndGet();
            bothCreating.await(10, TimeUnit.SECONDS);
            return "sesn_race_" + id;
        });

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(this::lookupInsideTransaction);
            var second = executor.submit(this::lookupInsideTransaction);
            SessionRef winner = first.get(20, TimeUnit.SECONDS);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(winner);
            assertThat(activeChatsToday()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT anthropic_session_id FROM agent_sessions WHERE id = ?",
                    String.class, winner.sessionRowId())).isEqualTo(winner.anthropicSessionId());
            verify(api, times(2)).createSession(anyMap());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void remoteFailureCreatesNoDatabaseRowAndIsNotRetried() throws Exception {
        when(api.createSession(anyMap())).thenThrow(new IOException("request failed"));
        assertThatThrownBy(() -> manager.todaySession(TOKYO)).isInstanceOf(IOException.class);
        assertThat(activeChatsToday()).isZero();
        verify(api).createSession(anyMap());
    }

    @Test
    void unrelatedUniqueViolationIsNotTreatedAsADailyRace() throws Exception {
        seed("sesn_duplicate", "chat", "active", TODAY.minusDays(1));
        when(api.createSession(anyMap())).thenReturn("sesn_duplicate");
        assertThatThrownBy(() -> manager.todaySession(TOKYO)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(activeChatsToday()).isZero();
    }

    private SessionRef lookupInsideTransaction() {
        return new TransactionTemplate(transactions).execute(status -> {
            try {
                SessionRef result = manager.todaySession(TOKYO);
                assertThat(status.isRollbackOnly()).isFalse();
                assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
                return result;
            } catch (IOException | InterruptedException error) {
                throw new IllegalStateException(error);
            }
        });
    }

    private UUID seed(String remoteId, String kind, String status, LocalDate day) {
        return jdbc.queryForObject("""
                INSERT INTO agent_sessions (anthropic_session_id, agent, agent_version, kind, status, local_day)
                VALUES (?, 'chat', 2, ?, ?, ?) RETURNING id
                """, UUID.class, remoteId, kind, status, day);
    }

    private int activeChatsToday() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM agent_sessions WHERE kind = 'chat' AND status = 'active' AND local_day = ?
                """, Integer.class, TODAY);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-05T15:30:00Z"), ZoneOffset.UTC);
        }
    }
}
