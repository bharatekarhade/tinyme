package com.tinyme.agent.model;

import java.util.Objects;
import java.util.UUID;
import java.time.ZoneId;
import java.time.Instant;

public record TurnRequest(SessionRef session, UUID userMessageId, String text, ZoneId zone, Instant sentAt) {
    public TurnRequest(SessionRef session, UUID userMessageId, String text, ZoneId zone) {
        this(session, userMessageId, text, zone, null);
    }

    public TurnRequest {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(userMessageId, "userMessageId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(zone, "zone");
    }
}
