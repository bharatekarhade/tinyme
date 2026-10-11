package com.tinyme.domain.entries.repository;

import com.tinyme.domain.entries.entity.EntryKindEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface EntryKindJpaRepository extends JpaRepository<EntryKindEntity, String> {
    List<EntryKindEntity> findByMergedIntoIsNullOrderByUseCountDescKindAsc(Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO entry_kinds (kind, use_count) VALUES (:kind, 1)
            ON CONFLICT (kind) DO UPDATE SET use_count = entry_kinds.use_count + 1
            """, nativeQuery = true)
    void upsertAndIncrement(@Param("kind") String kind);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO entry_kinds (kind, description, data_hints, use_count)
            VALUES (:kind, :description, CAST(:dataHints AS jsonb), 0)
            ON CONFLICT (kind) DO NOTHING
            """, nativeQuery = true)
    void insertCardKindIfMissing(@Param("kind") String kind, @Param("description") String description,
                                 @Param("dataHints") String dataHints);
}
