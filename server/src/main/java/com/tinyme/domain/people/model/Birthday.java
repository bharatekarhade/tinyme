package com.tinyme.domain.people.model;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Objects;

public record Birthday(MonthDay monthDay, Integer year) {
    public Birthday {
        Objects.requireNonNull(monthDay, "monthDay");
        if (year != null) {
            if (year < 1900 || year > 2100) {
                throw new IllegalArgumentException("birthday year must be between 1900 and 2100");
            }
            try {
                LocalDate.of(year, monthDay.getMonth(), monthDay.getDayOfMonth());
            } catch (java.time.DateTimeException error) {
                throw new IllegalArgumentException("birthday is not valid in the given year", error);
            }
        }
    }
}
