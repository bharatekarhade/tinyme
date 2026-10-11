package com.tinyme.api.people;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PersonDetailResponse(PersonResponse person,
                                   @JsonProperty("memory_content") String memoryContent,
                                   @JsonProperty("memory_available") boolean memoryAvailable,
                                   @JsonProperty("recent_entries") List<Object> recentEntries) {
    public PersonDetailResponse { recentEntries = List.copyOf(recentEntries); }
}
