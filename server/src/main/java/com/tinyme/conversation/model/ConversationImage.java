package com.tinyme.conversation.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A compressed image attachment sent with a chat message. */
public record ConversationImage(
        @JsonProperty("media_type") @NotBlank String mediaType,
        @Size(min = 1, max = 1_500_000) String data) {
}
