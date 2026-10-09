package com.tinyme.domain.people.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Entity
@Table(name = "people")
public class PersonEntity {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "aliases", nullable = false, columnDefinition = "text[]")
    private String[] aliases = new String[0];

    @Column(name = "relationship")
    private String relationship;

    @Column(name = "memory_path", nullable = false)
    private String memoryPath;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected PersonEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String[] getAliases() {
        return aliases.clone();
    }

    public String getRelationship() {
        return relationship;
    }

    public String getMemoryPath() {
        return memoryPath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setAliases(String[] aliases) {
        this.aliases = Arrays.copyOf(aliases, aliases.length);
    }

    public void setRelationship(String relationship) {
        this.relationship = relationship;
    }

    public void setMemoryPath(String memoryPath) {
        this.memoryPath = memoryPath;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
