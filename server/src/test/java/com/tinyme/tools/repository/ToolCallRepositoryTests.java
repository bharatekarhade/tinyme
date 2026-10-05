package com.tinyme.tools.repository;

import com.tinyme.tools.model.StoredCall;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "tinyme.tools.allow-missing-handlers=true"
})
@Import(ToolCallRepositoryTests.DatabaseConfiguration.class)
class ToolCallRepositoryTests {
    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ToolCallRepository toolCalls;

    @Test
    void persistsRunningCallAndFinishesItOncePerEvent() {
        UUID sessionId = jdbc.queryForObject("""
                INSERT INTO agent_sessions (anthropic_session_id, agent, agent_version, kind)
                VALUES (?, 'chat', 1, 'job')
                RETURNING id
                """, UUID.class, "test-session-" + UUID.randomUUID());
        String eventId = "test-event-" + UUID.randomUUID();
        var json = JsonMapper.builder().build();
        var input = json.readTree("{\"kind\":\"drink\"}");
        var result = json.readTree("{\"ok\":false,\"error\":\"test\"}");

        try {
            assertThat(toolCalls.findByEventId(eventId)).isEmpty();
            UUID callId = toolCalls.insertRunning(sessionId, eventId, "entries_add", input);
            assertThat(callId.version()).isEqualTo(7);

            StoredCall running = toolCalls.findByEventId(eventId).orElseThrow();
            assertThat(running.id()).isEqualTo(callId);
            assertThat(running.sessionRowId()).isEqualTo(sessionId);
            assertThat(running.tool()).isEqualTo("entries_add");
            assertThat(running.input().get("kind").stringValue()).isEqualTo("drink");
            assertThat(running.status()).isEqualTo("running");
            assertThat(running.startedAt()).isNotNull();
            assertThat(running.finishedAt()).isNull();

            assertThatThrownBy(() -> toolCalls.insertRunning(sessionId, eventId, "entries_add", input))
                    .isInstanceOf(DuplicateKeyException.class);

            toolCalls.finish(callId, result, true, "failed");
            StoredCall finished = toolCalls.findByEventId(eventId).orElseThrow();
            assertThat(finished.result().get("error").stringValue()).isEqualTo("test");
            assertThat(finished.isError()).isTrue();
            assertThat(finished.status()).isEqualTo("failed");
            assertThat(finished.finishedAt()).isNotNull();
        } finally {
            jdbc.update("DELETE FROM agent_sessions WHERE id = ?", sessionId);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18")
                    .asCompatibleSubstituteFor("postgres"));
        }
    }
}
