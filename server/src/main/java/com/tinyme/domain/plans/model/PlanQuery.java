package com.tinyme.domain.plans.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public record PlanQuery(LocalDate from, LocalDate to, PlanKind kind, String text,
                        String person, boolean includeDone) {
    public static final int MAX_DAYS = 62;
    public static final int MAX_RESULTS = 30;

    public PlanQuery {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) throw new IllegalArgumentException("to must be on or after from");
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException("date range must not exceed " + MAX_DAYS + " days");
        }
        if (text != null) {
            text = text.strip();
            if (text.isEmpty()) text = null;
        }
        if (person != null) {
            person = person.strip();
            if (person.isEmpty()) person = null;
        }
    }
}
