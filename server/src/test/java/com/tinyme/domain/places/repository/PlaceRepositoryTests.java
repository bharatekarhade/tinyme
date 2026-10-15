package com.tinyme.domain.places.repository;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.domain.places.entity.PlaceEntity;
import com.tinyme.domain.places.model.PlaceSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(AgentDatabaseConfiguration.class)
class PlaceRepositoryTests {
    private static final double ORIGIN_LAT = 35.6595;
    private static final double ORIGIN_LNG = 139.7005;
    private static final double EARTH_RADIUS_M = 6_378_168;

    @Autowired PlaceRepository places;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional
    void nearUsesEarthDistanceReturnsOrderedDistancesAndExcludesDeletedPlaces() {
        save("200 m", latOffset(200), "provider-200");
        save("900 m", latOffset(900), "provider-900");
        save("1.5 km", latOffset(1_500), "provider-1500");
        PlaceEntity deleted = save("deleted nearby", latOffset(100), "provider-deleted");
        deleted.softDelete(java.time.Instant.parse("2026-10-15T00:00:00Z"));
        entityManager.flush();
        entityManager.clear();

        var found = places.near(ORIGIN_LAT, ORIGIN_LNG, 1_000, 10);

        assertThat(found).extracting(item -> item.name()).containsExactly("200 m", "900 m");
        assertThat(found.get(0).distanceM()).isCloseTo(200.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(found.get(1).distanceM()).isCloseTo(900.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(found).extracting(item -> item.source()).containsOnly(PlaceSource.CHAT);
    }

    @Test
    @Transactional
    void nearQueryCanUseThePlacesGeoIndex() {
        for (int i = 0; i < 100; i++) {
            save("far " + i, ORIGIN_LAT + 1 + i * 0.00001, "far-provider-" + i);
        }
        entityManager.flush();
        jdbc.execute("SET LOCAL enable_seqscan = off");

        var plan = jdbc.queryForList("""
                EXPLAIN (COSTS OFF)
                SELECT p.id
                FROM places p
                WHERE p.deleted_at IS NULL
                  AND earth_box(ll_to_earth(?, ?), ?) @> ll_to_earth(p.lat, p.lng)
                  AND earth_distance(ll_to_earth(?, ?), ll_to_earth(p.lat, p.lng)) <= ?
                """, String.class, ORIGIN_LAT, ORIGIN_LNG, 1_000, ORIGIN_LAT, ORIGIN_LNG, 1_000);

        assertThat(String.join("\n", plan)).contains("places_geo");
    }

    private PlaceEntity save(String name, double lat, String providerId) {
        return places.save(new PlaceEntity(name, "Tokyo", null, lat, ORIGIN_LNG, null, providerId,
                "cafe", "https://example.test/" + providerId, PlaceSource.CHAT));
    }

    private static double latOffset(double metres) {
        return ORIGIN_LAT + Math.toDegrees(metres / EARTH_RADIUS_M);
    }
}
