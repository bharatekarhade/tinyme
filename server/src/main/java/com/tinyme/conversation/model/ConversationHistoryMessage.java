package com.tinyme.conversation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record ConversationHistoryMessage(UUID id, String role, String content, List<Object> actions,
                                         @JsonProperty("created_at") Instant createdAt) {
    public ConversationHistoryMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        role = role.toLowerCase(Locale.ROOT);
        Objects.requireNonNull(content, "content");
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
