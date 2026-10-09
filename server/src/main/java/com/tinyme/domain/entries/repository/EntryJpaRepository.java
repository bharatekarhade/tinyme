package com.tinyme.domain.entries.repository;

import com.tinyme.domain.entries.entity.EntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface EntryJpaRepository extends JpaRepository<EntryEntity, UUID> {
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
}

interface TodayTotalProjection {
    String getKind();

    String getType();

    BigDecimal getQuantity();
}
