package com.tinyme.domain.places.service;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.domain.places.model.PlaceSnapshot;
import com.tinyme.domain.places.model.PlaceSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(AgentDatabaseConfiguration.class)
class PlaceServiceTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-15T03:00:00Z");
    private static final double LAT = 35.6595;
    private static final double LNG = 139.7005;

    @Autowired PlaceService places;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearData() {
        jdbc.update("DELETE FROM places");
        jdbc.update("DELETE FROM pending_actions");
    }

    @Test
    void savesMapKitCandidateAndDuplicateProviderIdReturnsExistingAndAppendsNote() {
        UUID action = searchResult("""
                [{"name":"Blue Bottle Shibuya","address":"Shibuya, Tokyo","lat":35.6595,"lng":139.7005,
                  "category":"cafe","url":"https://example.test","provider_place_id":"mapkit-123","distance_m":250}]
                """, NOW.plusSeconds(7200));
        var first = places.saveCandidate(action + ":0", "for the pour-over", NOW);
        var duplicate = places.saveCandidate(action + ":0", "try the seasonal blend", NOW.plusSeconds(1));

        assertThat(first.alreadySaved()).isFalse();
        assertThat(first.place().name()).isEqualTo("Blue Bottle Shibuya");
        assertThat(first.place().address()).isEqualTo("Shibuya, Tokyo");
        assertThat(duplicate.alreadySaved()).isTrue();
        assertThat(duplicate.place().id()).isEqualTo(first.place().id());
        assertThat(duplicate.place().note()).isEqualTo("for the pour-over · also: try the seasonal blend");
        assertThat(jdbc.queryForObject("SELECT lat FROM places WHERE id = ?", Double.class, first.place().id()))
                .isEqualTo(LAT);
        assertThat(jdbc.queryForObject("SELECT source FROM places WHERE id = ?", String.class, first.place().id()))
                .isEqualTo("chat");
        assertThat(jdbc.queryForObject("SELECT expires_at > ? FROM pending_actions WHERE id = ?", Boolean.class,
                Timestamp.from(NOW.plusSeconds(7_199)), action)).isTrue();
    }

    @Test
    void savingTheSameReasonTwiceDoesNotRepeatItAndAStoredAreaIsSearchable() {
        UUID action = searchResult("""
                [{"name":"Blue Bottle Shibuya","address":"1 Jingumae Shibuya Tokyo 150-0001","area":"Jingumae, Shibuya",
                  "lat":35.6595,"lng":139.7005,"provider_place_id":"mapkit-area"}]
                """, NOW.plusSeconds(7200));
        places.saveCandidate(action + ":0", "for the pour-over", NOW);
        var again = places.saveCandidate(action + ":0", "For the pour-over", NOW.plusSeconds(1));

        assertThat(again.place().note()).isEqualTo("for the pour-over");
        assertThat(again.place().area()).isEqualTo("Jingumae, Shibuya");
        assertThat(places.list("jingumae", false)).extracting(PlaceSnapshot::id).containsExactly(again.place().id());
    }

    @Test
    void anAddedReasonThatWouldPassThreeHundredCharactersIsDropped() {
        String long1 = "a".repeat(200);
        String long2 = "b".repeat(150);
        assertThat(PlaceService.mergedNote(long1, long2)).isEqualTo(long1);
        assertThat(PlaceService.mergedNote("espresso", "beans")).isEqualTo("espresso · also: beans");
        assertThat(PlaceService.mergedNote(null, "beans")).isEqualTo("beans");
    }

    @Test
    void sameNameWithinFiftyMetresDeduplicatesButEightyMetresDoesNot() {
        UUID first = searchResult(candidate("Cafe A", LAT, LNG, null), NOW.plusSeconds(7200));
        var saved = places.saveCandidate(first + ":0", null, NOW);
        UUID nearby = searchResult(candidate("Cafe A", offsetLatitude(30), LNG, null), NOW.plusSeconds(7200));
        UUID farther = searchResult(candidate("Cafe A", offsetLatitude(80), LNG, null), NOW.plusSeconds(7200));

        assertThat(places.saveCandidate(nearby + ":0", null, NOW).place().id()).isEqualTo(saved.place().id());
        assertThat(places.saveCandidate(farther + ":0", null, NOW).place().id()).isNotEqualTo(saved.place().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM places", Integer.class)).isEqualTo(2);
    }

    @Test
    void expiredOrUnknownCandidateIsRejected() {
        UUID expired = searchResult(candidate("Expired", LAT, LNG, "expired"), NOW.minusSeconds(1));
        assertThatThrownBy(() -> places.saveCandidate(expired + ":0", null, NOW))
                .isInstanceOf(PlaceService.CandidateNotFoundException.class);
        assertThatThrownBy(() -> places.saveCandidate(UUID.randomUUID() + ":0", null, NOW))
                .isInstanceOf(PlaceService.CandidateNotFoundException.class);
        assertThatThrownBy(() -> places.saveCandidate("bad-id", null, NOW))
                .isInstanceOf(PlaceService.CandidateNotFoundException.class);
    }

    @Test
    void listFiltersVisitedAndSearchesNotesWhileNearUsesTurnLocation() {
        UUID action = searchResult("""
                [{"name":"Blue Bottle","address":"Shibuya, Tokyo","lat":35.6595,"lng":139.7005,
                  "category":"cafe","provider_place_id":"near-1"},
                 {"name":"Coffee Stand","address":"Shibuya, Tokyo","lat":35.6600,"lng":139.7005,
                  "category":"cafe","provider_place_id":"near-2"}]
                """, NOW.plusSeconds(7200));
        var first = places.saveCandidate(action + ":0", "espresso", NOW).place();
        var second = places.saveCandidate(action + ":1", "beans", NOW).place();
        places.update(first.id(), false, null, true, NOW.plusSeconds(1));

        assertThat(places.list("espresso", false)).isEmpty();
        assertThat(places.list("espresso", true)).extracting(place -> place.id()).containsExactly(first.id());
        assertThat(places.near(LAT, LNG, 1_000))
                .extracting(place -> place.id()).containsExactly(first.id(), second.id());
    }

    @Test
    void updateAndSoftDeleteReturnNotFoundAfterRemoval() {
        UUID action = searchResult(candidate("Blue Bottle", LAT, LNG, "remove-me"), NOW.plusSeconds(7200));
        var saved = places.saveCandidate(action + ":0", null, NOW).place();
        var updated = places.update(saved.id(), true, "visit soon", null, NOW.plusSeconds(1)).orElseThrow();
        assertThat(updated.note()).isEqualTo("visit soon");
        assertThat(places.remove(saved.id(), NOW.plusSeconds(2))).isPresent();
        assertThat(places.remove(saved.id(), NOW.plusSeconds(3))).isEmpty();
        assertThat(places.list(null, true)).isEmpty();
    }

    @Test
    void appPlaceListFiltersVisitStateAndComputesNearestFirstDistances() {
        UUID action = searchResult("""
                [{"name":"Near Cafe","address":"Shibuya, Tokyo","lat":35.6595,"lng":139.7005},
                 {"name":"Far Cafe","address":"Shinjuku, Tokyo","lat":35.6695,"lng":139.7005}]
                """, NOW.plusSeconds(7200));
        var near = places.saveCandidate(action + ":0", null, NOW).place();
        var far = places.saveCandidate(action + ":1", null, NOW).place();
        places.update(far.id(), false, null, true, NOW.plusSeconds(1));

        var wantToGo = places.listForApp("cafe", false, LAT, LNG);
        var visited = places.listForApp(null, true, null, null);

        assertThat(wantToGo).extracting(PlaceSnapshot::id).containsExactly(near.id());
        assertThat(wantToGo.getFirst().distanceM()).isNotNull().isLessThan(1.0);
        assertThat(visited).extracting(PlaceSnapshot::id).containsExactly(far.id());
        assertThat(places.listForApp(null, null, 35.6595, 139.7005))
                .extracting(PlaceSnapshot::id).containsExactly(near.id(), far.id());
    }

    @Test
    void watchReturnsTheTwentyNearestUnvisitedPlaces() {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int index = 0; index < 22; index++) {
            var candidate = new LinkedHashMap<String, Object>();
            candidate.put("name", "Place %02d".formatted(index));
            candidate.put("lat", offsetLatitude(index * 100));
            candidate.put("lng", LNG);
            candidate.put("provider_place_id", "watch-%d".formatted(index));
            candidates.add(candidate);
        }
        List<PlaceSnapshot> saved = new ArrayList<>();
        for (int offset = 0; offset < candidates.size(); offset += 5) {
            int end = Math.min(offset + 5, candidates.size());
            UUID action = searchResult(write(candidates.subList(offset, end)), NOW.plusSeconds(7200));
            for (int index = 0; index < end - offset; index++) {
                saved.add(places.saveCandidate(action + ":" + index, null, NOW).place());
            }
        }
        places.update(saved.getFirst().id(), false, null, true, NOW.plusSeconds(1));

        var watch = places.watch(LAT, LNG);

        assertThat(watch).hasSize(20).extracting(PlaceSnapshot::id)
                .containsExactlyElementsOf(saved.subList(1, 21).stream().map(PlaceSnapshot::id).toList());
        assertThat(watch).extracting(PlaceSnapshot::distanceM).isSorted();
    }

    private UUID searchResult(String data, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pending_actions (id, type, payload, origin, status, completed_at, expires_at, result)
                VALUES (?, 'places_search', '{}'::jsonb, 'chat', 'succeeded', ?, ?, jsonb_build_object('ok', true, 'data', ?::jsonb))
                """, id, Timestamp.from(NOW), Timestamp.from(expiresAt), data);
        return id;
    }

    private String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static String candidate(String name, double lat, double lng, String providerId) {
        try {
            var value = new java.util.LinkedHashMap<String, Object>();
            value.put("name", name);
            value.put("lat", lat);
            value.put("lng", lng);
            if (providerId != null) value.put("provider_place_id", providerId);
            return JSON.writeValueAsString(java.util.List.of(value));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static double offsetLatitude(double metres) {
        return LAT + Math.toDegrees(metres / 6_378_168d);
    }
}
