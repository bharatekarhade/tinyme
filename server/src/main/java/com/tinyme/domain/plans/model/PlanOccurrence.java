package com.tinyme.domain.plans.model;

import com.tinyme.domain.people.model.PersonSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One computed calendar item. originalDay keeps a moved occurrence's ID stable. */
public record PlanOccurrence(String id, UUID planId, LocalDate originalDay, LocalDate day,
                             LocalDate repeatStartDay,
                             LocalTime startTime, LocalTime endTime, LocalDate endDay,
                             Instant startsAt, Instant endsAt, boolean allDay, PlanKind kind,
                             String title, String place, List<PersonSnapshot> people,
                             OccurrenceStatus status, String habitKind, Repeat repeat, String source) {
    public PlanOccurrence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(originalDay, "originalDay");
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        people = List.copyOf(Objects.requireNonNull(people, "people"));
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(source, "source");
        if (repeat != null) Objects.requireNonNull(repeatStartDay, "repeatStartDay");
        if ((startsAt == null) != (endsAt == null)) {
            throw new IllegalArgumentException("startsAt and endsAt must both be set or both be null");
        }
        if (startsAt != null && endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("endsAt must not be before startsAt");
        }
    }
}
