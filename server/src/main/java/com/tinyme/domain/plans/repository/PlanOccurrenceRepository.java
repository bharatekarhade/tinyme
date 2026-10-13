package com.tinyme.domain.plans.repository;

import com.tinyme.domain.plans.entity.PlanOccurrenceEntity;
import com.tinyme.domain.plans.entity.PlanOccurrenceId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;

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

    @Transactional(readOnly = true)
    public List<PlanOccurrenceEntity> findRelevant(List<UUID> planIds, LocalDate from, LocalDate to) {
        if (planIds.isEmpty()) return List.of();
        return occurrences.findRelevant(planIds, Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to"));
    }

    @Transactional(readOnly = true)
    public List<PlanOccurrenceEntity> findMovedIntoRange(LocalDate from, LocalDate to) {
        return occurrences.findMovedIntoRange(Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to"));
    }

    @Transactional
    public void delete(PlanOccurrenceId id) {
        occurrences.deleteById(Objects.requireNonNull(id, "id"));
    }
}
