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
        "tinyme.agent.setup-enabled=false"
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
        UUID userId = messages.insert(sessionId, MessageRole.USER, "had a coffee");
        UUID assistantId = messages.insert(sessionId, MessageRole.ASSISTANT,
                "Logged coffee, 1 today.", actions);

        List<MessageEntity> saved = messages.findForSession(sessionId);

        assertThat(saved).extracting(MessageEntity::getId).containsExactly(userId, assistantId);
        assertThat(saved).extracting(MessageEntity::getSessionId).containsOnly(sessionId);
        assertThat(saved).extracting(MessageEntity::getRole)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
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
    void databaseRoleCheckRejectsUnknownRoles() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO messages (session_id, role, content) VALUES (?, 'SYSTEM', 'invalid role')
                """, sessionId)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
