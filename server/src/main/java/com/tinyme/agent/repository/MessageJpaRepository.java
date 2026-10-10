package com.tinyme.agent.repository;

import com.tinyme.agent.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.List;

interface MessageJpaRepository extends JpaRepository<MessageEntity, UUID> {
    List<MessageEntity> findBySession_IdOrderByCreatedAtAsc(UUID sessionId);
    List<MessageEntity> findBySession_IdInOrderByCreatedAtAscIdAsc(List<UUID> sessionIds);
    java.util.Optional<MessageEntity> findByClientMessageId(UUID clientMessageId);
}
