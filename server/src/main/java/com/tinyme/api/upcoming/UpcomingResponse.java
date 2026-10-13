package com.tinyme.api.upcoming;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record UpcomingResponse(Instant now, LocalDate today, LocalDate from, LocalDate to,
                               List<DaySummary> days,
                               @JsonProperty("next_up") OccurrenceResponse nextUp,
                               List<OccurrenceResponse> overdue,
                               List<OccurrenceResponse> items) {
    public UpcomingResponse {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        days = List.copyOf(Objects.requireNonNull(days, "days"));
        overdue = List.copyOf(Objects.requireNonNull(overdue, "overdue"));
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    public record DaySummary(String day, int count,
                             @JsonProperty("has_timed") boolean hasTimed) { }
}
