package com.tinyme.agent.repository;

import com.tinyme.agent.entity.MessageEntity;
import com.tinyme.agent.model.MessageRole;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.List;
import java.util.UUID;

@Repository
public class MessageRepository {
    private final MessageJpaRepository messages;
    private final AgentSessionJpaRepository sessions;

    MessageRepository(MessageJpaRepository messages, AgentSessionJpaRepository sessions) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Transactional
    public UUID insert(UUID sessionId, MessageRole role, String content) {
        return insert(sessionId, role, content, List.of());
    }

    @Transactional
    public UUID insertUser(UUID sessionId, UUID clientMessageId, String content) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(clientMessageId, "clientMessageId");
        MessageEntity message = MessageEntity.create(sessions.getReferenceById(sessionId), MessageRole.USER,
                content, List.of(), clientMessageId);
        return messages.save(message).getId();
    }

    @Transactional
    public UUID insert(UUID sessionId, MessageRole role, String content, List<?> actions) {
        Objects.requireNonNull(sessionId, "sessionId");
        MessageEntity message = MessageEntity.create(sessions.getReferenceById(sessionId), role, content, actions);
        return messages.save(message).getId();
    }

    @Transactional(readOnly = true)
    public List<MessageEntity> findForSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return messages.findBySession_IdOrderByCreatedAtAsc(sessionId);
    }
}
