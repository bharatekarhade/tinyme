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
    public UUID create(UUID sessionId, String threadId, UUID toolCallId, Map<String, Object> payload,
                       Duration lifetime) {
        jdbc.update("UPDATE confirmations SET status='expired', decided_at=statement_timestamp() "
                + "WHERE status='pending' AND expires_at <= statement_timestamp()");
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO confirmations (id, session_id, thread_id, tool_call_id, tool, payload, status, expires_at)
                    VALUES (?, ?, ?, ?, 'gmail_send', ?::jsonb, 'pending', statement_timestamp() + (? * INTERVAL '1 millisecond'))
                    """, id, sessionId, threadId, toolCallId, json(payload), lifetime.toMillis());
        } catch (DuplicateKeyException pendingExists) {
            throw new PendingConfirmationExistsException();
        }
        return id;
    }

    @Transactional(readOnly = true)
    public List<Confirmation> pending() {
        return jdbc.query("""
                SELECT id, payload, expires_at FROM confirmations
                WHERE status='pending' AND expires_at > statement_timestamp() ORDER BY created_at
                """, (rs, row) -> new Confirmation(rs.getObject("id", UUID.class),
                readJson(rs.getString("payload")), rs.getTimestamp("expires_at").toInstant()));
    }

    @Transactional
    public Optional<JsonNode> decide(UUID id, boolean approve) {
        String status = approve ? "approved" : "rejected";
        return jdbc.query("""
                UPDATE confirmations SET status=?, decided_at=statement_timestamp()
                WHERE id=? AND status='pending' AND expires_at > statement_timestamp()
                RETURNING payload
                """, rs -> rs.next() ? Optional.of(readJson(rs.getString(1))) : Optional.empty(), status, id);
    }

    @Transactional
    public boolean markFailed(UUID id) {
        return jdbc.update("UPDATE confirmations SET status='failed' WHERE id=? AND status='approved'", id) == 1;
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

    public record Confirmation(UUID id, JsonNode payload, java.time.Instant expiresAt) {}

    public static final class PendingConfirmationExistsException extends RuntimeException {}
}
