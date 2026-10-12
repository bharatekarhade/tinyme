package com.tinyme.domain.plans.repository;

import com.tinyme.domain.plans.entity.PlanOccurrenceEntity;
import com.tinyme.domain.plans.entity.PlanOccurrenceId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

@Repository
public class PlanOccurrenceRepository {
    private final PlanOccurrenceJpaRepository occurrences;

    PlanOccurrenceRepository(PlanOccurrenceJpaRepository occurrences) {
        this.occurrences = Objects.requireNonNull(occurrences, "occurrences");
    }

    @Transactional
    public PlanOccurrenceEntity save(PlanOccurrenceEntity occurrence) {
        return occurrences.save(Objects.requireNonNull(occurrence, "occurrence"));
    }

    @Transactional(readOnly = true)
    public Optional<PlanOccurrenceEntity> find(PlanOccurrenceId id) {
        return occurrences.findById(Objects.requireNonNull(id, "id"));
    }
}
