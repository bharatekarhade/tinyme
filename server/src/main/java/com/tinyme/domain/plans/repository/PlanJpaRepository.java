package com.tinyme.domain.plans.repository;

import com.tinyme.domain.plans.entity.PlanEntity;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.PlanSource;
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

    @EntityGraph(attributePaths = "people")
    Optional<PlanEntity> findByGoogleEventIdAndDeletedAtIsNull(String googleEventId);

    @EntityGraph(attributePaths = "people")
    Optional<PlanEntity> findBySourceAndExternalId(PlanSource source, String externalId);

    @EntityGraph(attributePaths = "people")
    @Query("select distinct p from PlanEntity p where p.deletedAt is null and p.status = com.tinyme.domain.plans.model.PlanStatus.ACTIVE and p.source <> :google and p.kind = :event and p.habitKind is null and ((p.repeatFreq is not null and (p.repeatUntil is null or p.repeatUntil >= :cutoff)) or (p.repeatFreq is null and coalesce(p.endDay, p.startDay) >= :cutoff)) and (p.googleEventId is null or p.updatedAt > p.googleSyncAt)")
    List<PlanEntity> findLiveMirrorCandidates(@Param("google") PlanSource google, @Param("event") PlanKind event,
                                              @Param("cutoff") LocalDate cutoff);

    @EntityGraph(attributePaths = "people")
    List<PlanEntity> findByGoogleEventIdNotNull();

    @EntityGraph(attributePaths = "people")
    @Query("select distinct p from PlanEntity p where p.googleEventId is not null and p.source <> com.tinyme.domain.plans.model.PlanSource.GOOGLE and (p.deletedAt is not null or p.status = com.tinyme.domain.plans.model.PlanStatus.CANCELLED)")
    List<PlanEntity> findPendingMirrorDeletes();
}
