package com.tinyme.tools.model;



import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record StoredCall(
        UUID id,
        UUID sessionRowId,
        String eventId,
        String tool,
        String agent,
        String threadId,
        JsonNode input,
        JsonNode result,
        boolean isError,
        String status,
        Instant startedAt,
        Instant finishedAt
) {
    public StoredCall(UUID id, UUID sessionRowId, String eventId, String tool, JsonNode input, JsonNode result,
                      boolean isError, String status, Instant startedAt, Instant finishedAt) {
        this(id, sessionRowId, eventId, tool, "tinyme-chat", null, input, result, isError, status,
                startedAt, finishedAt);
    }
}
