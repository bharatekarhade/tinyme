package com.tinyme.domain.entries.repository;

import com.tinyme.domain.entries.model.NewEntry;
import com.tinyme.domain.entries.entity.EntryEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Repository
public class EntryRepository {
    private final EntryJpaRepository entries;
    private final EntryKindJpaRepository kinds;

    EntryRepository(EntryJpaRepository entries,
                    EntryKindJpaRepository kinds) {
        this.entries = Objects.requireNonNull(entries, "entries");
        this.kinds = Objects.requireNonNull(kinds, "kinds");
    }

    @Transactional
    public UUID insert(NewEntry entry) {
        Objects.requireNonNull(entry, "entry");

        EntryEntity entity = new EntryEntity(
                entry.ts(),
                entry.localDay(),
                kinds.getReferenceById(entry.kind()));
        entity.setQuantity(entry.quantity());
        entity.setText(entry.text());
        entity.setData(entry.data());
        entity.setTags(entry.tags().toArray(String[]::new));
        entity.setSource(entry.source());

        return entries.save(entity).getId();
    }

    @Transactional(readOnly = true)
    public BigDecimal totalForDay(String kind,
                                  LocalDate day,
                                  String typeOrNull) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(day, "day");
        return entries.totalForDay(kind, day, typeOrNull);
    }
}
