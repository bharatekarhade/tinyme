package com.tinyme.api.people;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record PhotoResponse(UUID id,
                            String caption,
                            @JsonProperty("taken_at") Instant takenAt,
                            @JsonProperty("content_url") String contentUrl) {
}
