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

    public record DeletedHealth(UUID id, String kind) { }

    /** Soft-deletes the live Health entries with these ids and returns their database IDs and kinds. */
    public List<DeletedHealth> softDelete(Collection<String> externalIds, Instant now) {
        if (externalIds.isEmpty()) return List.of();
        return jdbc.query("""
                UPDATE entries SET deleted_at = :now
                WHERE source = 'health' AND deleted_at IS NULL AND external_id IN (:ids)
                RETURNING id, kind
                """, new MapSqlParameterSource("ids", externalIds).addValue("now", Timestamp.from(now)),
                (rs, row) -> new DeletedHealth(rs.getObject("id", UUID.class), rs.getString("kind")));
    }

    public record MergePair(UUID healthId, UUID chatId, String chatText, String chatType, String chatNote) { }

    /**
     * Chat workouts that are the same session as a Health workout: same day, logged from 3 h before it started
     * to 3 h after it ended. Nearest first, each Health workout at most once (one already merged is skipped).
     */
    public List<MergePair> workoutMergePairs(Collection<String> workoutKinds, LocalDate since) {
        if (workoutKinds.isEmpty()) return List.of();
        List<MergePair> pairs = new java.util.ArrayList<>();
        java.util.Set<UUID> usedHealth = new java.util.HashSet<>();
        java.util.Set<UUID> usedChat = new java.util.HashSet<>();
        jdbc.query("""
                SELECT h.id AS health_id, c.id AS chat_id, c.text AS chat_text,
                       c.data ->> 'type' AS chat_type, c.data ->> 'note' AS chat_note
                FROM entries h
                JOIN entries c ON c.kind IN (:kinds) AND c.local_day = h.local_day
                WHERE h.source = 'health' AND h.kind IN (:kinds) AND h.deleted_at IS NULL AND h.local_day >= :since
                  AND h.data -> 'merged_from' IS NULL
                  AND c.source <> 'health' AND c.deleted_at IS NULL
                  AND c.ts BETWEEN h.ts - interval '3 hours'
                      AND h.ts + make_interval(mins => COALESCE((h.data ->> 'minutes')::numeric, 0)::int) + interval '3 hours'
                ORDER BY abs(extract(epoch FROM c.ts - h.ts)), h.id, c.id
                """, new MapSqlParameterSource("kinds", workoutKinds).addValue("since", since), rs -> {
            UUID health = rs.getObject("health_id", UUID.class);
            UUID chat = rs.getObject("chat_id", UUID.class);
            if (usedHealth.contains(health) || usedChat.contains(chat)) return;
            usedHealth.add(health);
            usedChat.add(chat);
            pairs.add(new MergePair(health, chat, rs.getString("chat_text"), rs.getString("chat_type"),
                    rs.getString("chat_note")));
        });
        return pairs;
    }

    /** Folds a chat workout into the Health one: its words become the text and its type the label; it's soft-deleted. */
    public void merge(MergePair pair, Instant now) {
        Map<String, Object> kept = new LinkedHashMap<>();
        kept.put("merged_from", pair.chatId().toString());
        if (pair.chatType() != null && !pair.chatType().isBlank()) kept.put("label", pair.chatType());
        if (pair.chatNote() != null && !pair.chatNote().isBlank()) kept.put("note", pair.chatNote());
        jdbc.update("""
                UPDATE entries SET text = COALESCE(text, :text),
                    data = CAST(:kept AS jsonb) || data || jsonb_build_object('merged_from', :chatId)
                WHERE id = :healthId
                """, new MapSqlParameterSource("text", pair.chatText()).addValue("kept", JSON.writeValueAsString(kept))
                .addValue("chatId", pair.chatId().toString()).addValue("healthId", pair.healthId()));
        jdbc.update("UPDATE entries SET deleted_at = :now WHERE id = :chatId AND deleted_at IS NULL",
                new MapSqlParameterSource("now", Timestamp.from(now)).addValue("chatId", pair.chatId()));
    }

    public record SyncLogRow(String kind, Instant lastSyncAt, LocalDate lastDay, long itemsTotal) { }

    public List<SyncLogRow> syncLog() {
        return jdbc.query("SELECT kind, last_sync_at, last_day, items_total FROM health_sync_log ORDER BY kind",
                (rs, row) -> new SyncLogRow(rs.getString("kind"), rs.getTimestamp("last_sync_at").toInstant(),
                        rs.getObject("last_day", LocalDate.class), rs.getLong("items_total")));
    }

    /** Undeletes the chat workouts live Health ones absorbed (data.merged_from), before those are removed. */
    public int restoreMergedChatEntries() {
        return jdbc.update("""
                UPDATE entries c SET deleted_at = NULL
                FROM entries h
                WHERE h.source = 'health' AND h.deleted_at IS NULL AND h.data ->> 'merged_from' = c.id::text
                  AND c.source <> 'health' AND c.deleted_at IS NOT NULL
                """, Map.of());
    }

    /** Restores only chat workouts absorbed by the Health rows that this sync just deleted. */
    public int restoreMergedChatEntries(Collection<UUID> deletedHealthIds) {
        if (deletedHealthIds.isEmpty()) return 0;
        return jdbc.update("""
                UPDATE entries c SET deleted_at = NULL
                FROM entries h
                WHERE h.id IN (:healthIds) AND h.data ->> 'merged_from' = c.id::text
                  AND c.source <> 'health' AND c.deleted_at IS NOT NULL
                """, Map.of("healthIds", deletedHealthIds));
    }

    public int softDeleteAll(Instant now) {
        return jdbc.update("UPDATE entries SET deleted_at = :now WHERE source = 'health' AND deleted_at IS NULL",
                Map.of("now", Timestamp.from(now)));
    }

    public void clearSyncLog() {
        jdbc.update("DELETE FROM health_sync_log", Map.of());
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
