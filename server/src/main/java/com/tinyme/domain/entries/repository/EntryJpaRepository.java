package com.tinyme.domain.entries.repository;

import com.tinyme.domain.entries.entity.EntryEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface EntryJpaRepository extends JpaRepository<EntryEntity, UUID> {
    @Query("select e.kind.kind as kind, count(e) as entryCount from EntryEntity e " +
            "where e.deletedAt is null and e.localDay between :from and :to " +
            "group by e.kind.kind having count(e) >= :minimum " +
            "order by count(e) desc, e.kind.kind asc")
    List<RecentKindCount> recentKinds(@Param("from") LocalDate from,
                                     @Param("to") LocalDate to,
                                     @Param("minimum") long minimum);

    @Query(value = """
            SELECT COALESCE(SUM(quantity), 0)
            FROM entries
            WHERE kind = :kind
              AND local_day = :day
              AND deleted_at IS NULL
              AND (CAST(:typeOrNull AS text) IS NULL OR data ->> 'type' = CAST(:typeOrNull AS text))
            """, nativeQuery = true)
    BigDecimal totalForDay(@Param("kind") String kind,
                           @Param("day") LocalDate day,
                           @Param("typeOrNull") String typeOrNull);

    @Query(value = """
            SELECT kind AS kind, data ->> 'type' AS type, SUM(quantity) AS quantity
            FROM entries
            WHERE local_day = :day
              AND deleted_at IS NULL
            GROUP BY kind, data ->> 'type'
            ORDER BY kind, data ->> 'type' NULLS FIRST
            """, nativeQuery = true)
    List<TodayTotalProjection> todayTotals(@Param("day") LocalDate day);

    Optional<EntryEntity> findByIdAndDeletedAtIsNull(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select entry from EntryEntity entry where entry.id = :id")
    Optional<EntryEntity> findLockedById(@Param("id") UUID id);
}

interface RecentKindCount {
    String getKind();
    long getEntryCount();
}

interface TodayTotalProjection {
    String getKind();

    String getType();

    BigDecimal getQuantity();
}
