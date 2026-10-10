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
