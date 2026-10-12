package com.tinyme.agent.repository;

import com.tinyme.agent.entity.AgentSessionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AgentSessionJpaRepository extends JpaRepository<AgentSessionEntity, UUID> {
    List<AgentSessionEntity> findAllByOrderByIdAsc();
    Optional<AgentSessionEntity> findByKindAndStatusAndLocalDay(String kind, String status, LocalDate localDay);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AgentSessionEntity> findByIdAndKindAndLocalDay(UUID id, String kind, LocalDate localDay);
    List<AgentSessionEntity> findByKindAndLocalDayAndStatusNotOrderByIdAsc(
            String kind, LocalDate localDay, String excludedStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentSessionEntity session set session.status = 'deleted', session.endedAt = CURRENT_TIMESTAMP "
            + "where session.anthropicSessionId = :anthropicSessionId")
    int markDeletedByAnthropicSessionId(String anthropicSessionId);
}
