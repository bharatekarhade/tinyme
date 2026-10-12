package com.tinyme.domain.plans.repository;

import com.tinyme.domain.plans.entity.PlanEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PlanRepository {
    private final PlanJpaRepository plans;

    PlanRepository(PlanJpaRepository plans) {
        this.plans = Objects.requireNonNull(plans, "plans");
    }

    @Transactional
    public PlanEntity save(PlanEntity plan) {
        return plans.save(Objects.requireNonNull(plan, "plan"));
    }

    @Transactional(readOnly = true)
    public Optional<PlanEntity> findLive(UUID id) {
        return plans.findByIdAndDeletedAtIsNull(Objects.requireNonNull(id, "id"));
    }
}
