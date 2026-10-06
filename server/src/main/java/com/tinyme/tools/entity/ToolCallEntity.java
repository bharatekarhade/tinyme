package com.tinyme.tools.entity;



import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "tool_calls")
public class ToolCallEntity {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "anthropic_event_id", nullable = false, unique = true)
    private String anthropicEventId;

    @Column(nullable = false)
    private String tool;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> input;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(name = "is_error", nullable = false)
    private boolean error;

    @Column(nullable = false)
    private String status;

    @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ToolCallEntity() {}

    public static ToolCallEntity running(UUID sessionId, String eventId, String tool, Map<String, Object> input) {
        ToolCallEntity entity = new ToolCallEntity();
        entity.sessionId = sessionId;
        entity.anthropicEventId = eventId;
        entity.tool = tool;
        entity.input = input;
        entity.status = "running";
        return entity;
    }

    public UUID id() { return id; }
    public UUID sessionId() { return sessionId; }
    public String eventId() { return anthropicEventId; }
    public String tool() { return tool; }
    public Map<String, Object> input() { return input; }
    public Map<String, Object> result() { return result; }
    public boolean isError() { return error; }
    public String status() { return status; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }

    public void finish(Map<String, Object> result, boolean error, String status, Instant finishedAt) {
        this.result = result;
        this.error = error;
        this.status = status;
        this.finishedAt = finishedAt;
    }
}
