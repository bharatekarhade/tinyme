package com.tinyme.domain.places.repository;

import com.tinyme.domain.places.entity.PlaceEntity;
import com.tinyme.domain.places.model.NearbyPlace;
import com.tinyme.domain.places.model.PlaceSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PlaceRepository {
    private final PlaceJpaRepository places;
    private final JdbcTemplate jdbc;

    PlaceRepository(PlaceJpaRepository places, JdbcTemplate jdbc) {
        this.places = Objects.requireNonNull(places, "places");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Transactional
    public PlaceEntity save(PlaceEntity place) {
        return places.save(Objects.requireNonNull(place, "place"));
    }

    @Transactional(readOnly = true)
    public Optional<PlaceEntity> findLive(UUID id) {
        return places.findByIdAndDeletedAtIsNull(Objects.requireNonNull(id, "id"));
    }

    @Transactional(readOnly = true)
    public Optional<PlaceEntity> findByProviderId(String providerPlaceId) {
        if (providerPlaceId == null || providerPlaceId.isBlank()) return Optional.empty();
        return places.findByProviderPlaceIdAndDeletedAtIsNull(providerPlaceId);
    }

    @Transactional(readOnly = true)
    public Optional<PlaceEntity> findSameNameWithin(String name, double lat, double lng, int distanceM) {
        Objects.requireNonNull(name, "name");
        UUID id = jdbc.query("""
                SELECT p.id FROM places p
                WHERE p.deleted_at IS NULL AND lower(p.name) = lower(?)
                  AND earth_distance(ll_to_earth(?, ?), ll_to_earth(p.lat, p.lng)) <= ?
                ORDER BY p.id LIMIT 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, name, lat, lng, distanceM);
        return id == null ? Optional.empty() : findLive(id);
    }

    @Transactional(readOnly = true)
    public List<PlaceEntity> listLive(boolean includeVisited) {
        return includeVisited ? places.findAllByDeletedAtIsNullOrderByCreatedAtDescIdDesc()
                : places.findAllByDeletedAtIsNullAndVisitedAtIsNullOrderByCreatedAtDescIdDesc();
    }

    @Transactional(readOnly = true)
    public List<NearbyPlace> near(double lat, double lng, int radiusM, int limit) {
        validatePoint(lat, lng);
        if (radiusM < 1 || radiusM > 20_000) throw new IllegalArgumentException("radiusM must be between 1 and 20000");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return jdbc.query("""
                SELECT p.id, p.name, p.address, p.lat, p.lng, p.category, p.url, p.note, p.source,
                       p.visited_at, p.created_at,
                       earth_distance(ll_to_earth(?, ?), ll_to_earth(p.lat, p.lng)) AS distance_m
                FROM places p
                WHERE p.deleted_at IS NULL
                  AND earth_box(ll_to_earth(?, ?), ?) @> ll_to_earth(p.lat, p.lng)
                  AND earth_distance(ll_to_earth(?, ?), ll_to_earth(p.lat, p.lng)) <= ?
                ORDER BY distance_m ASC, p.id ASC
                LIMIT ?
                """, (rs, row) -> new NearbyPlace(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address"),
                rs.getDouble("lat"), rs.getDouble("lng"), rs.getString("category"), rs.getString("url"),
                rs.getString("note"), PlaceSource.valueOf(rs.getString("source").toUpperCase(Locale.ROOT)),
                rs.getTimestamp("visited_at") == null ? null : rs.getTimestamp("visited_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getDouble("distance_m")),
                lat, lng, lat, lng, radiusM, lat, lng, radiusM, limit);
    }

    private static void validatePoint(double lat, double lng) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) throw new IllegalArgumentException("lat must be between -90 and 90");
        if (!Double.isFinite(lng) || lng < -180 || lng > 180) throw new IllegalArgumentException("lng must be between -180 and 180");
    }
}
