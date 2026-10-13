package com.tinyme.conversation.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.util.UUID;
import java.time.Instant;

public record ConversationMessageRequest(
        @JsonProperty("client_msg_id") @NotNull UUID clientMessageId,
        @NotBlank @Size(min = 1, max = 4000) String text,
        @JsonProperty("client_context") @Valid ClientContext clientContext,
        @JsonProperty("sent_at") Instant sentAt) {

    public ConversationMessageRequest(UUID clientMessageId, String text, ClientContext clientContext) {
        this(clientMessageId, text, clientContext, null);
    }

    public record ClientContext(String tz) {
    }
}
