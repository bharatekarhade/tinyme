package com.tinyme.agent.repository;

import com.tinyme.agent.entity.MessageEntity;
import com.tinyme.agent.model.MessageRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Repository
public class MessageRepository {
    private final MessageJpaRepository messages;
    private final AgentSessionJpaRepository sessions;
    private final TransactionTemplate transactions;

    MessageRepository(MessageJpaRepository messages, AgentSessionJpaRepository sessions,
                      PlatformTransactionManager transactionManager) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Transactional
    public UUID insert(UUID sessionId, MessageRole role, String content) {
        return insert(sessionId, role, content, List.of());
    }

    public UUID insertUser(UUID sessionId, UUID clientMessageId, String content) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(clientMessageId, "clientMessageId");
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                MessageEntity message = MessageEntity.create(sessions.getReferenceById(sessionId), MessageRole.USER,
                        content, List.of(), clientMessageId);
                return messages.saveAndFlush(message).getId();
            }));
        } catch (DataIntegrityViolationException insertFailure) {
            var existingId = findIdByClientMessageId(clientMessageId);
            if (existingId.isPresent()) throw new DuplicateMessage(existingId.get());
            throw insertFailure;
        }
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findIdByClientMessageId(UUID clientMessageId) {
        Objects.requireNonNull(clientMessageId, "clientMessageId");
        return messages.findByClientMessageId(clientMessageId).map(MessageEntity::getId);
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

    @Transactional(readOnly = true)
    public List<MessageRecord> findForSessions(List<UUID> sessionIds) {
        Objects.requireNonNull(sessionIds, "sessionIds");
        if (sessionIds.isEmpty()) return List.of();
        return messages.findBySession_IdInOrderByCreatedAtAscIdAsc(sessionIds).stream()
                .map(message -> new MessageRecord(message.getId(), message.getRole(), message.getContent(),
                        message.getActions(), message.getCreatedAt()))
                .toList();
    }

    public record MessageRecord(UUID id, MessageRole role, String content, List<Object> actions,
                                java.time.Instant createdAt) {
    }

    public static final class DuplicateMessage extends RuntimeException {
        private final UUID existingId;

        public DuplicateMessage(UUID existingId) {
            super("Message client_msg_id was already accepted");
            this.existingId = Objects.requireNonNull(existingId, "existingId");
        }

        public UUID existingId() {
            return existingId;
        }
    }
}
