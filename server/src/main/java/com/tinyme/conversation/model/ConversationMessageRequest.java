package com.tinyme.conversation.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ConversationMessageRequest(
        @JsonProperty("client_msg_id") @NotNull UUID clientMessageId,
        @NotBlank @Size(min = 1, max = 4000) String text,
        @JsonProperty("client_context") @Valid ClientContext clientContext,
        @JsonProperty("sent_at") Instant sentAt,
        @Size(max = 1) List<@Valid ConversationImage> attachments) {

    public ConversationMessageRequest(UUID clientMessageId, String text, ClientContext clientContext) {
        this(clientMessageId, text, clientContext, null, List.of());
    }

    public ConversationMessageRequest(UUID clientMessageId, String text, ClientContext clientContext, Instant sentAt) {
        this(clientMessageId, text, clientContext, sentAt, List.of());
    }

    public ConversationMessageRequest {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public record ClientContext(String tz, @JsonProperty("location") ClientLocation location) {
        public ClientContext(String tz) {
            this(tz, null);
        }
    }

    public record ClientLocation(Double lat, Double lng,
                                 @JsonProperty("accuracy_m") Double accuracyM,
                                 Instant at, String area) {
    }
}
