package com.tinyme.tools.repository;

import com.tinyme.tools.model.ToolCallEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ToolCallJpaRepository extends JpaRepository<ToolCallEntity, UUID> {
    Optional<ToolCallEntity> findByAnthropicEventId(String eventId);
}
