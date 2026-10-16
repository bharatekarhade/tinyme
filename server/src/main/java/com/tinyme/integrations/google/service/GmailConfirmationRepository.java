package com.tinyme.integrations.google.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class GmailConfirmationRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public GmailConfirmationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public UUID create(UUID sessionId, String threadId, UUID toolCallId, String tool,
                       Map<String, Object> payload, Duration lifetime) {
        jdbc.update("UPDATE confirmations SET status='expired', decided_at=statement_timestamp() "
                + "WHERE status='pending' AND expires_at <= statement_timestamp()");
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO confirmations (id, session_id, thread_id, tool_call_id, tool, payload, status, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, 'pending', statement_timestamp() + (? * INTERVAL '1 millisecond'))
                    """, id, sessionId, threadId, toolCallId, tool, json(payload), lifetime.toMillis());
        } catch (DuplicateKeyException pendingExists) {
            throw new PendingConfirmationExistsException();
        }
        return id;
    }

    @Transactional(readOnly = true)
    public List<Confirmation> pending() {
        return jdbc.query("""
                SELECT id, tool, payload, expires_at FROM confirmations
                WHERE status='pending' AND expires_at > statement_timestamp() ORDER BY created_at
                """, (rs, row) -> new Confirmation(rs.getObject("id", UUID.class), rs.getString("tool"),
                readJson(rs.getString("payload")), rs.getTimestamp("expires_at").toInstant()));
    }

    @Transactional(readOnly = true)
    public boolean hasPendingDraft(String draftId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM confirmations "
                + "WHERE status='pending' AND expires_at > statement_timestamp() AND payload ->> 'draft_id' = ?)",
                Boolean.class, draftId));
    }

    @Transactional(readOnly = true)
    public Optional<JsonNode> pendingPayload(UUID id) {
        return jdbc.query("SELECT payload FROM confirmations WHERE id=? AND status='pending' "
                        + "AND expires_at > statement_timestamp()", rs -> rs.next()
                        ? Optional.of(readJson(rs.getString(1))) : Optional.empty(), id);
    }

    @Transactional
    public int expirePending(UUID id) {
        return jdbc.update("UPDATE confirmations SET status='expired', decided_at=statement_timestamp() "
                + "WHERE id=? AND status='pending'", id);
    }

    @Transactional
    public Optional<JsonNode> decide(UUID id, boolean approve) {
        // A rejected or completed confirmation is terminal; a later tap cannot reopen it.
        String status = approve ? "approved" : "rejected";
        return jdbc.query("""
                UPDATE confirmations SET status=?, decided_at=statement_timestamp()
                WHERE id=? AND status='pending' AND expires_at > statement_timestamp()
                RETURNING payload
                """, rs -> rs.next() ? Optional.of(readJson(rs.getString(1))) : Optional.empty(), status, id);
    }

    @Transactional
    public boolean markFailed(UUID id) {
        return jdbc.update("UPDATE confirmations SET status='failed', decided_at=statement_timestamp() "
                + "WHERE id=? AND status='approved'", id) == 1;
    }

    @Transactional
    public boolean markCompleted(UUID id, String status) {
        if (!List.of("sent", "responded").contains(status)) throw new IllegalArgumentException("invalid completion status");
        return jdbc.update("UPDATE confirmations SET status=?, decided_at=statement_timestamp() "
                + "WHERE id=? AND status='approved'", status, id) == 1;
    }

    @Transactional
    public List<RecentDecision> consumeRecentDecisions(UUID sessionId) {
        return jdbc.query("""
                UPDATE confirmations SET context_notified_at=statement_timestamp()
                WHERE id IN (SELECT id FROM confirmations WHERE session_id=? AND status IN ('sent','responded','rejected','failed')
                  AND context_notified_at IS NULL ORDER BY decided_at LIMIT 10)
                RETURNING payload, status, decided_at
                """, (rs, row) -> new RecentDecision(readJson(rs.getString("payload")), rs.getString("status"),
                rs.getTimestamp("decided_at").toInstant()), sessionId);
    }

    @Transactional
    public int expire(UUID id) {
        return jdbc.update("UPDATE confirmations SET status='expired', decided_at=statement_timestamp() "
                + "WHERE id=? AND status='pending' AND expires_at <= statement_timestamp()", id);
    }

    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Confirmation could not be serialized", failure); }
    }

    private static JsonNode readJson(String value) {
        try { return JSON.readTree(value); }
        catch (RuntimeException failure) { throw new IllegalStateException("Stored confirmation is invalid", failure); }
    }

    public record Confirmation(UUID id, String tool, JsonNode payload, java.time.Instant expiresAt) {}
    public record RecentDecision(JsonNode payload, String status, java.time.Instant decidedAt) {}

    public static final class PendingConfirmationExistsException extends RuntimeException {}
}
