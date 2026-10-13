package com.tinyme.domain.plans.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public sealed interface PlanChange permits PlanChange.Done, PlanChange.UndoDone, PlanChange.Skip,
        PlanChange.Move, PlanChange.Cancel, PlanChange.Edit {
    record Done(LocalDate occurrenceDay) implements PlanChange { }
    record UndoDone(LocalDate occurrenceDay) implements PlanChange { }
    record Skip(LocalDate occurrenceDay) implements PlanChange {
        public Skip { Objects.requireNonNull(occurrenceDay, "occurrenceDay"); }
    }
    record Move(LocalDate occurrenceDay, LocalDate day, LocalTime time) implements PlanChange {
        public Move {
            Objects.requireNonNull(occurrenceDay, "occurrenceDay");
            day = day == null ? occurrenceDay : day;
            if (day.equals(occurrenceDay) && time == null) {
                throw new IllegalArgumentException("nothing to move: give a new day or time");
            }
        }
    }
    record Cancel() implements PlanChange { }
    record Edit(Set<String> fields, String title, LocalDate day, LocalTime time,
                LocalTime endTime, LocalDate endDay, String place, List<String> people,
                String notes, Repeat repeat, String habitKind, Integer remindMinutes) implements PlanChange {
        public Edit {
            fields = Set.copyOf(Objects.requireNonNull(fields, "fields"));
            people = people == null ? List.of() : List.copyOf(people);
            if (fields.isEmpty()) throw new IllegalArgumentException("at least one plan field must be provided");
        }
    }
}
