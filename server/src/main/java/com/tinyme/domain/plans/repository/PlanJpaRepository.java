package com.tinyme.domain.plans.repository;

import com.tinyme.domain.plans.entity.PlanEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface PlanJpaRepository extends JpaRepository<PlanEntity, UUID> {
    @EntityGraph(attributePaths = "people")
    Optional<PlanEntity> findByIdAndDeletedAtIsNull(UUID id);
}
