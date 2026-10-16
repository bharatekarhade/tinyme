package com.tinyme.integrations.google.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Collection;
import java.util.UUID;

/** Durable cursors and run diagnostics for CalendarSync. */
@Repository
public class CalendarSyncRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public CalendarSyncRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc, "jdbc"); }

    public Optional<String> tinyMeCalendarId() {
        return jdbc.query("SELECT value ->> 'id' FROM settings WHERE key='google.calendar.tinyme'",
                result -> result.next() ? Optional.ofNullable(result.getString(1)) : Optional.empty());
    }

    public void setTinyMeCalendarId(String id) {
        try {
            jdbc.update("INSERT INTO settings (key, value) VALUES ('google.calendar.tinyme', ?::jsonb) "
                            + "ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value",
                    JSON.writeValueAsString(Map.of("id", Objects.requireNonNull(id, "id"))));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not save TinyMe calendar id", failure);
        }
    }

    public Optional<String> syncToken(String calendarId) {
        return jdbc.query("SELECT sync_token FROM calendar_sync_checkpoints WHERE calendar_id=?",
                statement -> statement.setString(1, calendarId), result ->
                        result.next() ? Optional.ofNullable(result.getString(1)) : Optional.empty());
    }

    public void saveSyncToken(String calendarId, String token) {
        jdbc.update("INSERT INTO calendar_sync_checkpoints (calendar_id, sync_token) VALUES (?, ?) "
                        + "ON CONFLICT (calendar_id) DO UPDATE SET sync_token=EXCLUDED.sync_token, "
                        + "updated_at=statement_timestamp()", calendarId, token);
    }

    public void clearSyncToken(String calendarId) {
        jdbc.update("DELETE FROM calendar_sync_checkpoints WHERE calendar_id=?", calendarId);
    }

    public UUID startRun() {
        jdbc.update("DELETE FROM calendar_sync_runs WHERE started_at < statement_timestamp() - interval '30 days'");
        return jdbc.queryForObject("INSERT INTO calendar_sync_runs (status) VALUES ('running') RETURNING id",
                UUID.class);
    }

    public int removeCheckpointsOutside(Collection<String> calendarIds) {
        Objects.requireNonNull(calendarIds, "calendarIds");
        if (calendarIds.isEmpty()) return jdbc.update("DELETE FROM calendar_sync_checkpoints");
        String placeholders = String.join(",", java.util.Collections.nCopies(calendarIds.size(), "?"));
        return jdbc.update("DELETE FROM calendar_sync_checkpoints WHERE calendar_id NOT IN (" + placeholders + ")",
                calendarIds.toArray());
    }

    @Transactional
    public void finishRun(UUID id, boolean success, String errorCode, Map<String, ?> details) {
        try {
            jdbc.update("UPDATE calendar_sync_runs SET status=?, finished_at=statement_timestamp(), error_code=?, "
                            + "details=?::jsonb WHERE id=?",
                    success ? "succeeded" : "failed", errorCode, JSON.writeValueAsString(details), id);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not record CalendarSync result", failure);
        }
    }

    /** After a complete full listing, cancel previously imported rows that no longer exist remotely. */
    public int cancelImportedCalendarSnapshotExcept(String calendarId, Collection<String> seenExternalIds) {
        Objects.requireNonNull(calendarId, "calendarId");
        Objects.requireNonNull(seenExternalIds, "seenExternalIds");
        StringBuilder sql = new StringBuilder("UPDATE plans SET status='cancelled', completed_at=NULL "
                + "WHERE source='google' AND deleted_at IS NULL "
                + "AND left(external_id, char_length(?) + 1)=? || ':' AND status <> 'cancelled'");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(calendarId);
        args.add(calendarId);
        if (!seenExternalIds.isEmpty()) {
            sql.append(" AND external_id NOT IN (")
                    .append(String.join(",", java.util.Collections.nCopies(seenExternalIds.size(), "?")))
                    .append(')');
            args.addAll(seenExternalIds);
        }
        return jdbc.update(sql.toString(), args.toArray());
    }
}
