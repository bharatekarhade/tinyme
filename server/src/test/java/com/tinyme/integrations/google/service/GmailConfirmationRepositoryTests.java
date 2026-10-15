package com.tinyme.integrations.google.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(GmailConfirmationRepositoryTests.DatabaseConfiguration.class)
class GmailConfirmationRepositoryTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired GmailConfirmationRepository confirmations;
    @Autowired com.tinyme.tools.repository.ToolCallRepository toolCalls;

    @Test
    void pendingConfirmationIsDurableAndCanBeDecidedOnce() {
        var ids = createCall();
        try {
            UUID confirmationId = confirmations.create(ids.session(), "gmail-thread", ids.call(),
                    Map.of("draft_id", "draft-1", "to", "aiko@example.test", "subject", "Friday",
                            "preview", "See you soon"), Duration.ofMinutes(10));

            assertThat(confirmations.pending()).singleElement().satisfies(row -> {
                assertThat(row.id()).isEqualTo(confirmationId);
                assertThat(row.payload().path("subject").asString()).isEqualTo("Friday");
            });
            assertThat(confirmations.decide(confirmationId, false)).isPresent()
                    .get().extracting(node -> node.path("draft_id").asString()).isEqualTo("draft-1");
            assertThat(confirmations.pending()).isEmpty();
            assertThat(confirmations.decide(confirmationId, true)).isEmpty();
        } finally {
            jdbc.update("DELETE FROM agent_sessions WHERE id=?", ids.session());
        }
    }

    @Test
    void onlyOneUnexpiredConfirmationCanBePending() {
        var ids = createCall();
        try {
            confirmations.create(ids.session(), null, ids.call(), Map.of("draft_id", "one"), Duration.ofMinutes(10));
            assertThatThrownBy(() -> confirmations.create(ids.session(), null, ids.call(),
                    Map.of("draft_id", "two"), Duration.ofMinutes(10)))
                    .isInstanceOf(GmailConfirmationRepository.PendingConfirmationExistsException.class);
        } finally {
            jdbc.update("DELETE FROM agent_sessions WHERE id=?", ids.session());
        }
    }

    private CallIds createCall() {
        UUID session = jdbc.queryForObject("""
                INSERT INTO agent_sessions (anthropic_session_id, agent, agent_version, kind)
                VALUES (?, 'chat', 1, 'job') RETURNING id
                """, UUID.class, "gmail-confirmation-" + UUID.randomUUID());
        UUID call = toolCalls.insertRunning(session, "confirm-event-" + UUID.randomUUID(),
                "gmail_send", tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode(),
                "tinyme-gmail", "gmail-thread");
        return new CallIds(session, call);
    }

    private record CallIds(UUID session, UUID call) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18")
                    .asCompatibleSubstituteFor("postgres"));
        }
    }
}
