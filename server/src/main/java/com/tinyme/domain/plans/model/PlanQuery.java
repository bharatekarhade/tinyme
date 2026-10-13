package com.tinyme.domain.plans.model;

import com.tinyme.domain.plans.service.PlanCalendar;

import java.time.LocalDate;
import java.util.Objects;

public record PlanQuery(LocalDate from, LocalDate to, PlanKind kind, String text,
                        String person, boolean includeDone) {
    public static final int MAX_DAYS = PlanCalendar.MAX_DAYS;
    public static final int MAX_RESULTS = 30;

    public PlanQuery {
        PlanCalendar.validateRange(from, to);
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
