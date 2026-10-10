package com.tinyme.agent.repository;

import com.tinyme.agent.entity.MessageEntity;
import com.tinyme.agent.model.MessageRole;
import com.tinyme.agent.support.AgentDatabaseConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(AgentDatabaseConfiguration.class)
class MessageRepositoryTests {
    @Autowired
    MessageRepository messages;

    @Autowired
    JdbcTemplate jdbc;

    private UUID sessionId;

    @BeforeEach
    void createSession() {
        jdbc.update("""
                INSERT INTO agent_sessions (anthropic_session_id, agent, agent_version, kind, local_day)
                VALUES (?, 'chat', 1, 'chat', ?)
                """, "sesn_message_test_" + UUID.randomUUID(), LocalDate.now());
        sessionId = jdbc.queryForObject("""
                SELECT id FROM agent_sessions WHERE anthropic_session_id LIKE 'sesn_message_test_%'
                ORDER BY created_at DESC LIMIT 1
                """, UUID.class);
    }

    @AfterEach
    void deleteSession() {
        if (sessionId != null) jdbc.update("DELETE FROM agent_sessions WHERE id = ?", sessionId);
    }

    @Test
    void persistsAndReadsUserAndAssistantWithJsonActionsAndAttachments() {
        var actions = List.of(Map.<String, Object>of(
                "tool", "entries_add", "summary", "Logged drink (coffee), 2 today", "isError", false));
        UUID clientMessageId = UUID.randomUUID();
        UUID userId = messages.insertUser(sessionId, clientMessageId, "had a coffee");
        UUID assistantId = messages.insert(sessionId, MessageRole.ASSISTANT,
                "Logged coffee, 1 today.", actions);

        List<MessageEntity> saved = messages.findForSession(sessionId);

        assertThat(saved).extracting(MessageEntity::getId).containsExactly(userId, assistantId);
        assertThat(saved).extracting(MessageEntity::getSessionId).containsOnly(sessionId);
        assertThat(saved).extracting(MessageEntity::getRole)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(saved.getFirst().getClientMessageId()).isEqualTo(clientMessageId);
        assertThat(saved.get(1).getClientMessageId()).isNull();
        assertThat(saved).extracting(MessageEntity::getContent)
                .containsExactly("had a coffee", "Logged coffee, 1 today.");
        assertThat(saved.getFirst().getActions()).isEmpty();
        assertThat(saved.getFirst().getAttachments()).isEmpty();
        assertThat(saved.get(1).getActions()).isEqualTo(actions);
        assertThat(saved).allSatisfy(message -> assertThat(message.getCreatedAt()).isNotNull());
        assertThat(jdbc.queryForList("SELECT role FROM messages WHERE session_id = ? ORDER BY created_at", String.class, sessionId))
                .containsExactly("USER", "ASSISTANT");
    }

    @Test
    void rejectsUnknownSessionForeignKey() {
        assertThatThrownBy(() -> messages.insert(UUID.randomUUID(), MessageRole.USER, "orphan"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateClientMessageIdReturnsExistingMessageId() {
        UUID clientMessageId = UUID.randomUUID();
        UUID existingId = messages.insertUser(sessionId, clientMessageId, "had a coffee");

        assertThatThrownBy(() -> messages.insertUser(sessionId, clientMessageId, "had a beer"))
                .isInstanceOfSatisfying(MessageRepository.DuplicateMessage.class,
                        duplicate -> assertThat(duplicate.existingId()).isEqualTo(existingId));
    }

    @Test
    void historyReadReturnsLowercaseConvertibleRolesAndActionsAcrossSessionIds() {
        var actions = List.of(Map.<String, Object>of(
                "tool", "entries_add", "summary", "Logged drink", "isError", false));
        UUID userId = messages.insertUser(sessionId, UUID.randomUUID(), "had a coffee");
        UUID assistantId = messages.insert(sessionId, MessageRole.ASSISTANT, "Logged coffee.", actions);

        var history = messages.findForSessions(List.of(sessionId));

        assertThat(history).extracting(MessageRepository.MessageRecord::id)
                .containsExactly(userId, assistantId);
        assertThat(history).extracting(row -> row.role().name().toLowerCase(java.util.Locale.ROOT))
                .containsExactly("user", "assistant");
        assertThat(history.get(1).actions()).isEqualTo(actions);
        assertThat(messages.findForSessions(List.of())).isEmpty();
    }

    @Test
    void historyUsesUuidAsTieBreakerWhenCreatedAtMatches() {
        UUID first = messages.insert(sessionId, MessageRole.USER, "first");
        UUID second = messages.insert(sessionId, MessageRole.ASSISTANT, "second");
        jdbc.update("UPDATE messages SET created_at = TIMESTAMPTZ '2026-10-10 00:00:00+00' WHERE id IN (?, ?)",
                first, second);

        List<UUID> databaseOrder = jdbc.queryForList(
                "SELECT id FROM messages WHERE id IN (?, ?) ORDER BY created_at ASC, id ASC", UUID.class, first, second);
        List<UUID> historyOrder = messages.findForSessions(List.of(sessionId)).stream()
                .filter(message -> message.id().equals(first) || message.id().equals(second))
                .map(MessageRepository.MessageRecord::id).toList();

        assertThat(historyOrder).containsExactlyElementsOf(databaseOrder);
    }

    @Test
    void databaseRoleCheckRejectsUnknownRoles() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO messages (session_id, role, content) VALUES (?, 'SYSTEM', 'invalid role')
                """, sessionId)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
