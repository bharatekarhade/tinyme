package com.tinyme.agent.repository;

import com.tinyme.agent.model.DeviceToolResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PendingActionRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public PendingActionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Transactional
    public UUID insertDelivered(UUID toolCallId, String tool, Map<String, Object> input, Duration timeout) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pending_actions
                    (id, tool_call_id, type, payload, origin, status, delivered_at, expires_at)
                VALUES (?, ?, ?, ?::jsonb, 'chat', 'delivered', statement_timestamp(),
                        statement_timestamp() + (? * INTERVAL '1 millisecond'))
                """, id, toolCallId, tool, writeJson(input), timeout.toMillis());
        return id;
    }

    @Transactional
    public boolean complete(UUID id, DeviceToolResult result) {
        Objects.requireNonNull(result, "result");
        String status = result.succeeded() ? "succeeded" : "failed";
        return jdbc.update("""
                UPDATE pending_actions
                SET status = ?, result = ?::jsonb, completed_at = statement_timestamp()
                WHERE id = ? AND status = 'delivered' AND expires_at > statement_timestamp()
                """, status, writeJson(resultJson(result)), id) == 1;
    }

    @Transactional
    public boolean expire(UUID id) {
        return jdbc.update("""
                UPDATE pending_actions SET status = 'expired', completed_at = statement_timestamp()
                WHERE id = ? AND status = 'delivered'
                """, id) == 1;
    }

    @Transactional(readOnly = true)
    public Optional<String> status(UUID id) {
        return jdbc.query("SELECT status FROM pending_actions WHERE id = ?", rs ->
                rs.next() ? Optional.of(rs.getString("status")) : Optional.empty(), id);
    }

    private static Map<String, Object> resultJson(DeviceToolResult result) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ok", result.succeeded());
        if (result.succeeded()) json.put("data", result.data());
        else json.put("error", Map.of("code", result.errorCode(), "message", result.errorMessage()));
        return json;
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Device action data could not be serialized", failure);
        }
    }
}
