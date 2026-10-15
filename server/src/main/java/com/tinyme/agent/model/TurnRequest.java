package com.tinyme.agent.model;

import java.util.Objects;
import java.util.UUID;
import java.time.ZoneId;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TurnRequest(SessionRef session, UUID userMessageId, String text, ZoneId zone, Instant sentAt,
                          List<Map<String, Object>> imageBlocks, UUID attachedPhotoId) {
    public TurnRequest(SessionRef session, UUID userMessageId, String text, ZoneId zone) {
        this(session, userMessageId, text, zone, null, List.of(), null);
    }

    public TurnRequest(SessionRef session, UUID userMessageId, String text, ZoneId zone, Instant sentAt) {
        this(session, userMessageId, text, zone, sentAt, List.of(), null);
    }

    public TurnRequest(SessionRef session, UUID userMessageId, String text, ZoneId zone, Instant sentAt,
                       List<Map<String, Object>> imageBlocks) {
        this(session, userMessageId, text, zone, sentAt, imageBlocks, null);
    }

    public TurnRequest {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(userMessageId, "userMessageId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(zone, "zone");
        imageBlocks = List.copyOf(Objects.requireNonNull(imageBlocks, "imageBlocks"));
    }
}
