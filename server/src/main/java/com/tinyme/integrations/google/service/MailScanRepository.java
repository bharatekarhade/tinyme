package com.tinyme.integrations.google.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MailScanRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public MailScanRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<ScanState> state() {
        return jdbc.query("SELECT history_id, initial_page_token, initial_complete, history_page_token, "
                        + "pending_history_id, pending_ids::text FROM mail_scan_state WHERE id=1",
                result -> result.next() ? Optional.of(new ScanState(result.getString(1), result.getString(2),
                        result.getBoolean(3), result.getString(4), strings(result.getString(6)),
                        result.getString(5)))
                        : Optional.empty());
    }

    public void startInitialScan(String historyId) {
        jdbc.update("INSERT INTO mail_scan_state (id, history_id, initial_complete) VALUES (1, ?, false) "
                + "ON CONFLICT (id) DO NOTHING", historyId);
    }

    public void saveInitialPage(String nextPageToken, boolean complete) {
        jdbc.update("UPDATE mail_scan_state SET initial_page_token=?, initial_complete=?, "
                        + "updated_at=statement_timestamp() WHERE id=1",
                nextPageToken, complete);
    }

    public void saveHistoryProgress(List<String> pendingIds, String pageToken, String latestHistoryId) {
        try {
            jdbc.update("UPDATE mail_scan_state SET pending_ids=?::jsonb, history_page_token=?, "
                            + "pending_history_id=?, updated_at=statement_timestamp() WHERE id=1",
                    JSON.writeValueAsString(pendingIds), pageToken, latestHistoryId);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Could not persist Gmail scan backlog", failure);
        }
    }

    public void saveHistoryId(String historyId) {
        jdbc.update("INSERT INTO mail_scan_state (id, history_id, initial_complete) VALUES (1, ?, true) "
                        + "ON CONFLICT (id) DO UPDATE SET history_id=EXCLUDED.history_id, "
                        + "initial_page_token=NULL, initial_complete=true, history_page_token=NULL, "
                        + "pending_history_id=NULL, pending_ids='[]'::jsonb, updated_at=statement_timestamp()",
                historyId);
    }

    public boolean scanned(String messageId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM scanned_messages WHERE gmail_message_id=? "
                        + "AND (outcome <> 'failed' OR attempt_count >= 2))", Boolean.class, messageId));
    }

    public void markScanned(String messageId, String outcome) {
        jdbc.update("INSERT INTO scanned_messages (gmail_message_id, outcome) VALUES (?, ?) "
                        + "ON CONFLICT (gmail_message_id) DO UPDATE SET outcome=EXCLUDED.outcome", messageId, outcome);
    }

    /** Returns true when this was the second extraction failure and the message is now terminal. */
    public boolean markExtractionFailure(String messageId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                INSERT INTO scanned_messages (gmail_message_id, outcome, attempt_count)
                VALUES (?, 'failed', 1)
                ON CONFLICT (gmail_message_id) DO UPDATE
                  SET attempt_count=LEAST(scanned_messages.attempt_count + 1, 2),
                      outcome='failed', scanned_at=statement_timestamp()
                RETURNING attempt_count >= 2
                """, Boolean.class, messageId));
    }

    public int scannedSince(Instant start) {
        return jdbc.queryForObject("SELECT count(*) FROM scanned_messages WHERE scanned_at >= ?",
                Integer.class, Timestamp.from(start));
    }

    public UUID startRun() {
        jdbc.update("DELETE FROM mail_scan_runs WHERE started_at < statement_timestamp() - interval '30 days'");
        jdbc.update("DELETE FROM scanned_messages WHERE scanned_at < statement_timestamp() - interval '90 days'");
        return jdbc.queryForObject("INSERT INTO mail_scan_runs (status) VALUES ('running') RETURNING id", UUID.class);
    }

    public void expireSuggestions(LocalDate today) {
        jdbc.update("UPDATE suggestions SET status='expired' WHERE status='new' "
                + "AND (payload ->> 'day')::date < ?", today);
    }

    public void finishRun(UUID id, String status, int scanned, int suggested, int rejected, String errorCode) {
        jdbc.update("UPDATE mail_scan_runs SET status=?, scanned=?, suggested=?, rejected=?, error_code=?, "
                        + "finished_at=statement_timestamp() WHERE id=?",
                status, scanned, suggested, rejected, errorCode, id);
    }

    public boolean saveSuggestion(String messageId, Candidate candidate) {
        try {
            return jdbc.update("INSERT INTO suggestions (gmail_message_id, type, payload, confidence, evidence) "
                            + "VALUES (?, ?, ?::jsonb, ?, ?) ON CONFLICT (gmail_message_id, payload) DO NOTHING",
                    messageId, candidate.type(), JSON.writeValueAsString(candidate.payload()),
                    candidate.confidence(), candidate.evidence()) == 1;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Could not save mail suggestion", failure);
        }
    }

    public List<Suggestion> listNew() {
        return jdbc.query("SELECT id, gmail_message_id, type, payload, confidence, evidence, created_at "
                        + "FROM suggestions WHERE status='new' ORDER BY created_at DESC LIMIT 100",
                (result, row) -> suggestion(result.getObject("id", UUID.class), result.getString("gmail_message_id"),
                        result.getString("type"), result.getString("payload"), result.getDouble("confidence"),
                        result.getString("evidence"), result.getTimestamp("created_at").toInstant()));
    }

    public Optional<Suggestion> findNew(UUID id) {
        return jdbc.query("SELECT id, gmail_message_id, type, payload, confidence, evidence, created_at "
                        + "FROM suggestions WHERE id=? AND status='new'", statement -> statement.setObject(1, id),
                result -> result.next() ? Optional.of(suggestion(result.getObject("id", UUID.class),
                        result.getString("gmail_message_id"), result.getString("type"), result.getString("payload"),
                        result.getDouble("confidence"), result.getString("evidence"),
                        result.getTimestamp("created_at").toInstant())) : Optional.empty());
    }

    public boolean accept(UUID id, UUID planId) {
        return jdbc.update("UPDATE suggestions SET status='accepted', plan_id=? WHERE id=? AND status='new'",
                planId, id) == 1;
    }

    public boolean dismiss(UUID id) {
        return jdbc.update("UPDATE suggestions SET status='dismissed' WHERE id=? AND status='new'", id) == 1;
    }

    public boolean similarPlanExists(LocalDate day, String title) {
        // “Similar” is deliberately deterministic: same start day and exact title after whitespace folding/lowercasing.
        String normalized = title.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM plans "
                        + "WHERE deleted_at IS NULL AND status <> 'cancelled' AND start_day=? "
                        + "AND lower(regexp_replace(title, '\\s+', ' ', 'g'))=?)",
                Boolean.class, day, normalized));
    }

    private static Suggestion suggestion(UUID id, String messageId, String type, String payload,
                                         double confidence, String evidence, Instant createdAt) {
        try {
            JsonNode node = JSON.readTree(payload);
            List<String> people = new ArrayList<>();
            JsonNode names = node.path("people");
            if (names.isArray()) for (JsonNode name : names) if (name.isString()) people.add(name.asString());
            return new Suggestion(id, messageId, type, text(node, "title"), LocalDate.parse(text(node, "day")),
                    text(node, "time"), text(node, "end_time"),
                    text(node, "end_day") == null ? null : LocalDate.parse(text(node, "end_day")), text(node, "source_time"),
                    text(node, "source_time_zone"), text(node, "place"), people,
                    confidence, evidence, createdAt);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored mail suggestion is invalid", invalid);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isString() ? null : value.asString();
    }

    private static List<String> strings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode values = JSON.readTree(json);
            List<String> result = new ArrayList<>();
            if (values.isArray()) for (JsonNode value : values) if (value.isString()) result.add(value.asString());
            return List.copyOf(result);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored Gmail scan backlog is invalid", invalid);
        }
    }

    public record ScanState(String historyId, String initialPageToken, boolean initialComplete,
                            String historyPageToken, List<String> pendingIds, String pendingHistoryId) {
        public ScanState { pendingIds = List.copyOf(pendingIds); }
    }
    public record Candidate(String type, Map<String, Object> payload, double confidence, String evidence) { }
    public record Suggestion(UUID id, String gmailMessageId, String type, String title, LocalDate day,
                             String time, String endTime, LocalDate endDay, String sourceTime, String sourceTimeZone,
                             String place, List<String> people,
                             double confidence, String evidence, Instant createdAt) {
        public Suggestion { people = List.copyOf(people); }
    }
}
