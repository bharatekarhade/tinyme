package com.tinyme.domain.entries.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "entry_kinds")
public class EntryKindEntity {
    @Id
    private String kind;

    @Column(name = "description")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_hints", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> dataHints = new LinkedHashMap<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merged_into")
    private EntryKindEntity mergedInto;

    @Column(name = "use_count", nullable = false)
    private int useCount;

    // PostgreSQL supplies created_at through DEFAULT now(); Hibernate reads it back.
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected EntryKindEntity() {
    }

    public EntryKindEntity(String kind) {
        this.kind = kind;
        this.useCount = 1;
    }

    public String getKind() {
        return kind;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getDataHints() {
        return dataHints;
    }

    public String mergedIntoKind() {
        return mergedInto == null ? null : mergedInto.getKind();
    }

    public void incrementUseCount() {
        useCount++;
    }
}
