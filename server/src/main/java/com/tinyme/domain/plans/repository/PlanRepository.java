package com.tinyme.domain.plans.repository;

import com.tinyme.domain.people.entity.PersonEntity;
import com.tinyme.domain.plans.entity.PlanEntity;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Repository
public class PlanRepository {
    private final PlanJpaRepository plans;
    private final EntityManager entityManager;

    PlanRepository(PlanJpaRepository plans, EntityManager entityManager) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Transactional
    public PlanEntity save(PlanEntity plan) {
        return plans.save(Objects.requireNonNull(plan, "plan"));
    }

    @Transactional
    public PlanEntity save(PlanEntity plan, Set<String> personSlugs) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(personSlugs, "personSlugs");
        if (!personSlugs.isEmpty()) {
            List<PersonEntity> resolved = entityManager.createQuery("""
                            select person from PersonEntity person
                            where person.deletedAt is null and person.slug in :slugs
                            """, PersonEntity.class)
                    .setParameter("slugs", personSlugs)
                    .getResultList();
            if (resolved.size() != personSlugs.size()) {
                Set<String> found = resolved.stream().map(PersonEntity::getSlug).collect(Collectors.toSet());
                TreeSet<String> missing = new TreeSet<>(personSlugs);
                missing.removeAll(found);
                throw new IllegalArgumentException("People no longer available: " + String.join(", ", missing));
            }
            plan.replacePeople(Set.copyOf(resolved));
        } else {
            plan.replacePeople(Set.of());
        }
        return plans.save(plan);
    }

    @Transactional(readOnly = true)
    public Optional<PlanEntity> findLive(UUID id) {
        return plans.findByIdAndDeletedAtIsNull(Objects.requireNonNull(id, "id"));
    }

    @Transactional(readOnly = true)
    public Optional<PlanEntity> findAny(UUID id) {
        return plans.findById(Objects.requireNonNull(id, "id"));
    }

    @Transactional(readOnly = true)
    public List<PlanEntity> findForCalendar(java.time.LocalDate from, java.time.LocalDate to) {
        return plans.findCalendarPlans(Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to"));
    }

    @Transactional(readOnly = true)
    public List<PlanEntity> findLiveByIds(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return plans.findLiveByIds(ids);
    }
}
