package com.tinyme.api.admin;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeleteAllCounts(long entries, long people, long cards, long messages, long sessions,
                              @JsonProperty("memory_documents") long memoryDocuments) { }
