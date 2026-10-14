package com.tinyme.domain.health.repository;

import com.tinyme.domain.health.model.HealthItem;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Entries with source 'health', found and written by their external id. */
@Repository
public class HealthEntryRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;

    public HealthEntryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Stored(UUID id, String externalId, String kind, LocalDate localDay, Instant ts,
                         BigDecimal quantity, Map<String, Object> data) { }

    /** One sync at a time, held until the transaction ends, so two syncs can't insert the same id. */
    public void lockSync() {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('tinyme.health_sync'))", rs -> { });
    }

    public Map<String, Stored> findLive(Collection<String> externalIds) {
        Map<String, Stored> found = new LinkedHashMap<>();
        if (externalIds.isEmpty()) return found;
        jdbc.query("""
                SELECT id, external_id, kind, local_day, ts, quantity, data::text AS data
                FROM entries
                WHERE source = 'health' AND deleted_at IS NULL AND external_id IN (:ids)
                """, Map.of("ids", externalIds), rs -> {
            found.put(rs.getString("external_id"), new Stored(rs.getObject("id", UUID.class),
                    rs.getString("external_id"), rs.getString("kind"), rs.getObject("local_day", LocalDate.class),
                    rs.getTimestamp("ts").toInstant(), rs.getBigDecimal("quantity"),
                    JSON.readValue(rs.getString("data"), MAP)));
        });
        return found;
    }

    public void insert(HealthItem item, String kind, Map<String, Object> data) {
        jdbc.update("""
                INSERT INTO entries (ts, local_day, kind, quantity, data, source, external_id)
                VALUES (:ts, :day, :kind, :quantity, CAST(:data AS jsonb), 'health', :externalId)
                """, params(item, kind, data).addValue("externalId", item.externalId()));
    }

    public void update(UUID id, HealthItem item, String kind, Map<String, Object> data) {
        jdbc.update("""
                UPDATE entries SET ts = :ts, local_day = :day, kind = :kind, quantity = :quantity,
                                   data = CAST(:data AS jsonb)
                WHERE id = :id
                """, params(item, kind, data).addValue("id", id));
    }

    /** Soft-deletes the live Health entries with these ids and returns the kind of each one deleted. */
    public List<String> softDelete(Collection<String> externalIds, Instant now) {
        if (externalIds.isEmpty()) return List.of();
        return jdbc.queryForList("""
                UPDATE entries SET deleted_at = :now
                WHERE source = 'health' AND deleted_at IS NULL AND external_id IN (:ids)
                RETURNING kind
                """, new MapSqlParameterSource("ids", externalIds).addValue("now", Timestamp.from(now)), String.class);
    }

    /** A Health entry counts as a use of its kind, like one logged in chat. */
    public void addUses(String kind, int count) {
        jdbc.update("UPDATE entry_kinds SET use_count = use_count + :count WHERE kind = :kind",
                Map.of("kind", kind, "count", count));
    }

    /** Recounts what is stored for a kind, so Settings can say "Steps: synced 5 min ago, 90 days". */
    public void refreshSyncLog(String kind, Instant now) {
        jdbc.update("""
                INSERT INTO health_sync_log (kind, last_sync_at, last_day, items_total)
                SELECT :kind, :now, max(local_day), count(*)
                FROM entries WHERE source = 'health' AND kind = :kind AND deleted_at IS NULL
                ON CONFLICT (kind) DO UPDATE SET last_sync_at = EXCLUDED.last_sync_at,
                    last_day = EXCLUDED.last_day, items_total = EXCLUDED.items_total
                """, new MapSqlParameterSource("kind", kind).addValue("now", Timestamp.from(now)));
    }

    private static MapSqlParameterSource params(HealthItem item, String kind, Map<String, Object> data) {
        return new MapSqlParameterSource()
                .addValue("ts", Timestamp.from(item.ts()))
                .addValue("day", item.localDay())
                .addValue("kind", kind)
                .addValue("quantity", item.quantity())
                .addValue("data", JSON.writeValueAsString(data));
    }
}
