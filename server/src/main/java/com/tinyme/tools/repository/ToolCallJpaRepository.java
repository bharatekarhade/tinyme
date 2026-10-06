package com.tinyme.tools.repository;

import com.tinyme.tools.entity.ToolCallEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ToolCallJpaRepository extends JpaRepository<ToolCallEntity, UUID> {
    Optional<ToolCallEntity> findByAnthropicEventId(String eventId);
}
