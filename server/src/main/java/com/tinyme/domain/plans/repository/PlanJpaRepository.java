package com.tinyme.domain.plans.repository;

import com.tinyme.domain.plans.entity.PlanEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PlanJpaRepository extends JpaRepository<PlanEntity, UUID> {
    @EntityGraph(attributePaths = "people")
    Optional<PlanEntity> findByIdAndDeletedAtIsNull(UUID id);

    @EntityGraph(attributePaths = "people")
    @Query("""
            select distinct p from PlanEntity p
            where p.deletedAt is null and p.startDay <= :to
              and ((p.repeatFreq is null and coalesce(p.endDay, p.startDay) >= :from)
                or (p.repeatFreq is not null and (p.repeatUntil is null or p.repeatUntil >= :from))
                or (p.kind = com.tinyme.domain.plans.model.PlanKind.TASK
                    and p.status = com.tinyme.domain.plans.model.PlanStatus.ACTIVE and p.startDay < :from))
            """)
    List<PlanEntity> findCalendarPlans(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @EntityGraph(attributePaths = "people")
    @Query("select distinct p from PlanEntity p where p.deletedAt is null and p.id in :ids")
    List<PlanEntity> findLiveByIds(@Param("ids") List<UUID> ids);
}
