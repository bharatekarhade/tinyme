package com.tinyme.conversation.service;

import com.tinyme.agent.client.FakeManagedAgents;
import com.tinyme.agent.client.ManagedAgents;
import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.conversation.controller.SseTurnSink;
import com.tinyme.conversation.model.ConversationMessageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import({AgentDatabaseConfiguration.class, ConversationDisconnectTests.FakeApiConfiguration.class})
class ConversationDisconnectTests {
    @Autowired ConversationService conversations;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepareDatabase() {
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM tool_calls");
        jdbc.update("DELETE FROM entries");
        jdbc.update("DELETE FROM entry_kinds");
        jdbc.update("DELETE FROM agent_sessions");
        setting("env.default", "{\"id\":\"env_test\"}");
        setting("agent.chat", "{\"id\":\"agent_test\",\"version\":1}");
        setting("memory.main", "{\"id\":\"mem_test\"}");
        setting("user.tz", "\"Asia/Tokyo\"");
    }

    @Test
    void disconnectedClientDoesNotCancelEntryCreationOrAssistantMessage() throws Exception {
        var request = new ConversationMessageRequest(UUID.randomUUID(), "had a coffee",
                new ConversationMessageRequest.ClientContext("Asia/Tokyo"));
        var accepted = (ConversationService.Accepted) conversations.accept(request);
        var emitter = new SseEmitter() {
            private int sends;

            @Override
            public void send(SseEventBuilder builder) throws IOException {
                if (++sends == 2) throw new IOException("simulated client disconnect");
            }
        };
        var sink = new SseTurnSink(emitter);
        sink.start(accepted.userMessageId());

        conversations.runAsync(accepted, sink).whenComplete(sink::complete).get();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM entries WHERE kind = 'drink' AND deleted_at IS NULL",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT role FROM messages ORDER BY created_at", String.class))
                .containsExactly("USER", "ASSISTANT");
        assertThat(jdbc.queryForObject("SELECT actions::text FROM messages WHERE role = 'ASSISTANT'",
                String.class)).contains("entries_add", "Logged drink");

        var history = conversations.history(null);
        assertThat(history.messages()).extracting(message -> message.role())
                .containsExactly("user", "assistant");
        assertThat(history.messages().get(1).actions().toString()).contains("entries_add", "Logged drink");

        jdbc.update("""
                INSERT INTO agent_sessions (anthropic_session_id, agent, agent_version, kind, local_day, status)
                VALUES ('sesn_deleted_history_test', 'chat', 1, 'chat', ?, 'deleted')
                """, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")));
        UUID deletedSession = jdbc.queryForObject(
                "SELECT id FROM agent_sessions WHERE anthropic_session_id = 'sesn_deleted_history_test'", UUID.class);
        jdbc.update("INSERT INTO messages (session_id, role, content) VALUES (?, 'USER', 'deleted conversation')",
                deletedSession);

        var historyAfterReset = conversations.history(null);
        assertThat(historyAfterReset.messages()).hasSize(2);
        assertThat(historyAfterReset.messages()).noneMatch(message -> message.content().equals("deleted conversation"));
    }

    @Test
    void offlineQueuedMessageUsesItsOriginalSendTimeForDefaultEntryTimestamp() throws Exception {
        Instant queuedAt = Instant.now().minus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var request = new ConversationMessageRequest(UUID.randomUUID(), "had a coffee",
                new ConversationMessageRequest.ClientContext("Asia/Tokyo"), queuedAt);
        var accepted = (ConversationService.Accepted) conversations.accept(request);

        conversations.runAsync(accepted, com.tinyme.agent.service.TurnListener.NONE).get();

        OffsetDateTime storedAt = jdbc.queryForObject(
                "SELECT ts FROM entries WHERE kind = 'drink' AND deleted_at IS NULL",
                OffsetDateTime.class);
        LocalDate storedDay = jdbc.queryForObject(
                "SELECT local_day FROM entries WHERE kind = 'drink' AND deleted_at IS NULL",
                LocalDate.class);
        assertThat(storedAt.toInstant()).isEqualTo(queuedAt);
        assertThat(storedDay).isEqualTo(LocalDate.ofInstant(queuedAt, ZoneId.of("Asia/Tokyo")));
    }

    @Test
    void acceptsOnlyRecentAccurateLocationAndDropsInvalidFixesWithoutRejectingMessage() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var validRequest = new ConversationMessageRequest(UUID.randomUUID(), "anything nearby?",
                new ConversationMessageRequest.ClientContext("Asia/Tokyo",
                        new ConversationMessageRequest.ClientLocation(35.6595, 139.7005, 35.0,
                                now.minusSeconds(120), "Shibuya, Tokyo")), null, java.util.List.of());
        var valid = (ConversationService.Accepted) conversations.accept(validRequest);
        assertThat(valid.location()).isEqualTo(new com.tinyme.agent.model.TurnLocation(
                35.6595, 139.7005, 35.0, now.minusSeconds(120), "Shibuya, Tokyo"));
        valid.permit().close();

        var stale = acceptLocation(now.minus(Duration.ofMinutes(11)), 35.0, 35.6595, 139.7005);
        var inaccurate = acceptLocation(now, 500.0, 35.6595, 139.7005);
        // A phone clock a little fast is fine; a fix from well after the message was sent is not.
        var slightlyAhead = acceptLocation(now.plusSeconds(30), 35.0, 35.6595, 139.7005);
        var future = acceptLocation(now.plus(Duration.ofMinutes(3)), 35.0, 35.6595, 139.7005);
        var outOfRange = acceptLocation(now, 35.0, 91.0, 139.7005);

        assertThat(stale.location()).isNull();
        assertThat(inaccurate.location()).isNull();
        assertThat(slightlyAhead.location()).isNotNull();
        assertThat(future.location()).isNull();
        assertThat(outOfRange.location()).isNull();
        slightlyAhead.permit().close();
        stale.permit().close();
        inaccurate.permit().close();
        future.permit().close();
        outOfRange.permit().close();
    }

    @Test
    void aQueuedMessageKeepsOnlyALocationFromWhenItWasSent() throws Exception {
        Instant sentAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(30));
        var fromThen = acceptQueued(sentAt, sentAt.minusSeconds(60));
        var fromUpload = acceptQueued(sentAt, sentAt.plus(Duration.ofMinutes(30)));

        assertThat(fromThen.location()).isNotNull();
        assertThat(fromUpload.location()).as("where the phone is now is not where they said it").isNull();
        fromThen.permit().close();
        fromUpload.permit().close();
    }

    private ConversationService.Accepted acceptQueued(Instant sentAt, Instant at) throws Exception {
        var request = new ConversationMessageRequest(UUID.randomUUID(), "anything nearby?",
                new ConversationMessageRequest.ClientContext("Asia/Tokyo",
                        new ConversationMessageRequest.ClientLocation(35.6595, 139.7005, 35.0, at, "Tokyo")),
                sentAt, java.util.List.of());
        return (ConversationService.Accepted) conversations.accept(request);
    }

    private ConversationService.Accepted acceptLocation(Instant at, double accuracy,
                                                       double lat, double lng) throws Exception {
        var request = new ConversationMessageRequest(UUID.randomUUID(), "where am I?",
                new ConversationMessageRequest.ClientContext("Asia/Tokyo",
                        new ConversationMessageRequest.ClientLocation(lat, lng, accuracy, at, "Tokyo")),
                null, java.util.List.of());
        return (ConversationService.Accepted) conversations.accept(request);
    }

    private void setting(String key, String json) {
        jdbc.update("INSERT INTO settings (key, value) VALUES (?, CAST(? AS jsonb)) "
                        + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", key, json);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeApiConfiguration {
        @Bean
        @Primary
        ManagedAgents fixtureManagedAgents() throws IOException {
            return FakeManagedAgents.fromResource(ConversationDisconnectTests.class,
                    "/streams/had-a-coffee.json").build();
        }
    }
}
