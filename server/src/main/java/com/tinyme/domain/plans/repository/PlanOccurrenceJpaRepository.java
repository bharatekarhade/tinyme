package com.tinyme.domain.plans.repository;

import com.tinyme.domain.plans.entity.PlanOccurrenceEntity;
import com.tinyme.domain.plans.entity.PlanOccurrenceId;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlanOccurrenceJpaRepository extends JpaRepository<PlanOccurrenceEntity, PlanOccurrenceId> {
}
