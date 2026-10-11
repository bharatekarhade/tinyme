package com.tinyme.domain.cards.model.view;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record LatestEntryView(Instant ts, @JsonProperty("local_day") LocalDate localDay,
                              String text, Map<String, Object> data) {
    public LatestEntryView { data = Collections.unmodifiableMap(new LinkedHashMap<>(data)); }
}
