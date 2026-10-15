package com.tinyme.domain.places.entity;

import com.tinyme.domain.places.model.PlaceSource;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "places")
public class PlaceEntity {
    public static final int MAX_NOTE_LENGTH = 300;

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address")
    private String address;

    @Column(name = "area")
    private String area;

    @Column(name = "lat", nullable = false)
    private double lat;

    @Column(name = "lng", nullable = false)
    private double lng;

    @Column(name = "note")
    private String note;

    @Column(name = "provider_place_id")
    private String providerPlaceId;

    @Column(name = "category")
    private String category;

    @Column(name = "url")
    private String url;

    @Column(name = "source", nullable = false)
    @Convert(converter = PlaceSourceConverter.class)
    private PlaceSource source = PlaceSource.CHAT;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "visited_at")
    private Instant visitedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected PlaceEntity() { }

    public PlaceEntity(String name, String address, String area, double lat, double lng, String note,
                       String providerPlaceId, String category, String url, PlaceSource source) {
        setName(name);
        setCoordinates(lat, lng);
        this.address = address;
        this.area = area == null || area.isBlank() ? null : area.strip();
        updateNote(note);
        this.providerPlaceId = providerPlaceId;
        this.category = category;
        this.url = url;
        this.source = Objects.requireNonNull(source, "source");
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
    public String getNote() { return note; }
    public String getProviderPlaceId() { return providerPlaceId; }
    public String getCategory() { return category; }
    public String getUrl() { return url; }
    public PlaceSource getSource() { return source; }
    public String getArea() { return area; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getVisitedAt() { return visitedAt; }
    public Instant getDeletedAt() { return deletedAt; }

    public void updateNote(String note) {
        ensureLive();
        String clean = note == null || note.isBlank() ? null : note.strip();
        if (clean != null && clean.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("note must be at most %d characters".formatted(MAX_NOTE_LENGTH));
        }
        this.note = clean;
    }

    public void markVisited(Instant now) {
        ensureLive();
        if (visitedAt == null) visitedAt = Objects.requireNonNull(now, "now");
    }

    public void unmarkVisited() {
        ensureLive();
        visitedAt = null;
    }

    public void softDelete(Instant now) {
        if (deletedAt == null) deletedAt = Objects.requireNonNull(now, "now");
    }

    private void setName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        String clean = name.strip();
        if (clean.length() > 200) throw new IllegalArgumentException("name must be at most 200 characters");
        this.name = clean;
    }

    private void setCoordinates(double lat, double lng) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) throw new IllegalArgumentException("lat must be between -90 and 90");
        if (!Double.isFinite(lng) || lng < -180 || lng > 180) throw new IllegalArgumentException("lng must be between -180 and 180");
        this.lat = lat;
        this.lng = lng;
    }

    private void ensureLive() {
        if (deletedAt != null) throw new IllegalStateException("Deleted places cannot be changed");
    }
}
