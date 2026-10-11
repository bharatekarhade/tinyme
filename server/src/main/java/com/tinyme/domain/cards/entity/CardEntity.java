package com.tinyme.domain.cards.entity;

import com.tinyme.domain.cards.model.CardStatus;
import jakarta.persistence.Convert;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "cards")
public class CardEntity {
    private static final String JOURNAL_SLUG = "journal";

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "title", nullable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> spec = new LinkedHashMap<>();

    @Column(name = "status", nullable = false)
    @Convert(converter = CardStatusConverter.class)
    private CardStatus status;

    @Column(name = "system", nullable = false)
    private boolean system;

    @Column(name = "position", nullable = false)
    private int position;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected CardEntity() {
    }

    public CardEntity(String slug, String title, Map<String, Object> spec, CardStatus status,
                      boolean system, int position, Instant activatedAt) {
        if (slug == null || slug.isBlank()) throw new IllegalArgumentException("slug is required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        if (spec == null) throw new IllegalArgumentException("spec is required");
        if (status == null) throw new IllegalArgumentException("status is required");
        if (position < 0) throw new IllegalArgumentException("position must not be negative");
        if ((status == CardStatus.ACTIVE) != (activatedAt != null)) {
            throw new IllegalArgumentException("active cards require activatedAt; drafts must not have it");
        }
        if (JOURNAL_SLUG.equals(slug) && !system) {
            throw new IllegalArgumentException("slug 'journal' is reserved for the system card; choose another title");
        }
        this.slug = slug;
        this.title = title;
        this.spec = new LinkedHashMap<>(spec);
        this.status = status;
        this.system = system;
        this.position = position;
        this.activatedAt = activatedAt;
    }

    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getTitle() { return title; }
    public Map<String, Object> getSpec() { return Map.copyOf(spec); }
    public CardStatus getStatus() { return status; }
    public boolean isSystem() { return system; }
    public int getPosition() { return position; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getDeletedAt() { return deletedAt; }

    public void activate(int position, Instant now) {
        if (status == CardStatus.ACTIVE) return;
        if (deletedAt != null) throw new IllegalStateException("Deleted cards cannot be activated");
        if (position < 0) throw new IllegalArgumentException("position must not be negative");
        this.position = position;
        this.status = CardStatus.ACTIVE;
        this.activatedAt = java.util.Objects.requireNonNull(now, "now");
    }

    public void replaceSpec(String title, Map<String, Object> spec) {
        if (system || JOURNAL_SLUG.equals(slug)) {
            throw new IllegalStateException("The Journal system card cannot be changed; choose another title");
        }
        if (deletedAt != null) throw new IllegalStateException("Deleted cards cannot be changed");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        if (spec == null) throw new IllegalArgumentException("spec is required");
        this.title = title;
        this.spec = new LinkedHashMap<>(spec);
    }

    /** Replaces only code-owned Journal content, preserving its identity and placement. */
    public void syncSystemSpec(String title, Map<String, Object> spec) {
        if (!system || !JOURNAL_SLUG.equals(slug)) {
            throw new IllegalStateException("Only the Journal system card can use system seed synchronization");
        }
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        if (spec == null) throw new IllegalArgumentException("spec is required");
        this.title = title;
        this.spec = new LinkedHashMap<>(spec);
    }

    public void softDelete(Instant now) {
        if (system || JOURNAL_SLUG.equals(slug)) {
            throw new IllegalStateException("The Journal system card cannot be deleted");
        }
        if (deletedAt == null) this.deletedAt = java.util.Objects.requireNonNull(now, "now");
    }

    public void moveTo(int position) {
        if (position < 0) throw new IllegalArgumentException("position must not be negative");
        this.position = position;
    }
}
