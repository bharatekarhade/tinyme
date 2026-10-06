package com.tinyme.tools.repository;

import com.tinyme.tools.model.StoredCall;
import com.tinyme.tools.entity.ToolCallEntity;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class ToolCallRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> FINISHED_STATUSES = Set.of("done", "failed");

    private final ToolCallJpaRepository repository;

    public ToolCallRepository(ToolCallJpaRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<StoredCall> findByEventId(String eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return repository.findByAnthropicEventId(eventId).map(ToolCallRepository::toStoredCall);
    }

    /**
     * Inserts a running call. A duplicate event ID is translated to
     * DuplicateKeyException so callers can treat it as an already-seen event.
     */
    @Transactional
    public UUID insertRunning(UUID sessionRowId, String eventId, String tool, JsonNode input) {
        Objects.requireNonNull(sessionRowId, "sessionRowId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(input, "input");

        ToolCallEntity entity = ToolCallEntity.running(sessionRowId, eventId, tool, asMap(input));
        try {
            return repository.saveAndFlush(entity).id();
        } catch (DataIntegrityViolationException exception) {
            if (isUniqueViolation(exception)) {
                throw new DuplicateKeyException("Tool call event already exists", exception);
            }
            throw exception;
        }
    }

    @Transactional
    public void finish(UUID id, JsonNode resultJson, boolean isError, String status) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(resultJson, "resultJson");
        Objects.requireNonNull(status, "status");
        if (!FINISHED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Tool call status must be 'done' or 'failed'");
        }

        ToolCallEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No tool call found with id '" + id + "'"));
        entity.finish(asMap(resultJson), isError, status, Instant.now());
        repository.saveAndFlush(entity);
    }

    private static StoredCall toStoredCall(ToolCallEntity entity) {
        return new StoredCall(entity.id(), entity.sessionId(), entity.eventId(), entity.tool(),
                JSON.valueToTree(entity.input()),
                entity.result() == null ? null : JSON.valueToTree(entity.result()),
                entity.isError(), entity.status(), entity.startedAt(), entity.finishedAt());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(JsonNode value) {
        if (!value.isObject()) {
            throw new IllegalArgumentException("Tool call JSON values must be objects");
        }
        return JSON.readValue(value.toString(), Map.class);
    }

    private static boolean isUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
