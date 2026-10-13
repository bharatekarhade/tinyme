package com.tinyme.domain.plans.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

public record PlanAddCommand(PlanKind kind, String title, LocalDate day, LocalTime time,
                             LocalTime endTime, LocalDate endDay, String place, List<String> people,
                             String notes, Repeat repeat, String habitKind, Integer remindMinutes) {
    public PlanAddCommand {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        title = title.strip();
        if (title.isBlank()) throw new IllegalArgumentException("title is required");
        Objects.requireNonNull(day, "day");
        people = List.copyOf(people == null ? List.of() : people);
    }
}
