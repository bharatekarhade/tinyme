package com.tinyme.domain.plans.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Structured recurrence rule; a null Repeat on a plan means that plan occurs once. */
public record Repeat(RepeatFrequency freq, int interval, Set<DayOfWeek> days, LocalDate until) {
    public Repeat {
        Objects.requireNonNull(freq, "freq");
        if (interval < 1 || interval > 52) {
            throw new IllegalArgumentException("repeat interval must be between 1 and 52");
        }
        EnumSet<DayOfWeek> orderedDays = days == null || days.isEmpty()
                ? EnumSet.noneOf(DayOfWeek.class)
                : EnumSet.copyOf(days);
        days = Collections.unmodifiableSet(orderedDays);
        if (freq != RepeatFrequency.WEEKLY && !days.isEmpty()) {
            throw new IllegalArgumentException("repeat days are only valid for weekly repeats");
        }
    }
}
