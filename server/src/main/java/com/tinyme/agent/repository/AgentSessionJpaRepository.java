package com.tinyme.agent.repository;

import com.tinyme.agent.entity.AgentSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

interface AgentSessionJpaRepository extends JpaRepository<AgentSessionEntity, UUID> {
    Optional<AgentSessionEntity> findByKindAndStatusAndLocalDay(String kind, String status, LocalDate localDay);
}
