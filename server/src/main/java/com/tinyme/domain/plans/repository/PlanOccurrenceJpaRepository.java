package com.tinyme.domain.plans.repository;

import com.tinyme.domain.plans.entity.PlanOccurrenceEntity;
import com.tinyme.domain.plans.entity.PlanOccurrenceId;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface PlanOccurrenceJpaRepository extends JpaRepository<PlanOccurrenceEntity, PlanOccurrenceId> {
    @EntityGraph(attributePaths = {"plan", "plan.people"})
    @Query("""
            select occurrence from PlanOccurrenceEntity occurrence
            where occurrence.plan.id in :planIds
              and (occurrence.id.day between :from and :to
                or occurrence.movedDay between :from and :to)
            """)
    List<PlanOccurrenceEntity> findRelevant(@Param("planIds") List<UUID> planIds,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to);

    @EntityGraph(attributePaths = {"plan", "plan.people"})
    @Query("""
            select occurrence from PlanOccurrenceEntity occurrence
            where occurrence.plan.deletedAt is null and occurrence.movedDay between :from and :to
            """)
    List<PlanOccurrenceEntity> findMovedIntoRange(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
