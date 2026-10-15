package com.tinyme.domain.places.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reads device-owned MapKit results retained on the completed places_search action. */
@Repository
public class PlaceCandidateRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public PlaceCandidateRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc, "jdbc"); }

    @Transactional(readOnly = true)
    public Optional<JsonNode> find(UUID actionId, int index, Instant now) {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(now, "now");
        String data = jdbc.query("""
                SELECT result->'data' AS data FROM pending_actions
                WHERE id = ? AND type = 'places_search' AND status = 'succeeded' AND expires_at > ?
                """, rs -> rs.next() ? rs.getString("data") : null, actionId, Timestamp.from(now));
        if (data == null) return Optional.empty();
        try {
            JsonNode array = JSON.readTree(data);
            if (array == null || !array.isArray() || index < 0 || index >= array.size()) return Optional.empty();
            return Optional.of(array.get(index));
        } catch (RuntimeException invalidStoredResult) {
            return Optional.empty();
        }
    }
}
