package com.tinyme.domain.entries.model.aggregate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public enum Period {
    RANGE, TODAY, YESTERDAY, THIS_WEEK, LAST_7_DAYS, THIS_MONTH, LAST_30_DAYS, LAST_12_WEEKS, THIS_YEAR;

    public DateRange resolve(Range range, LocalDate today) {
        Objects.requireNonNull(today, "today");
        return switch (this) {
            case RANGE -> resolveRange(Objects.requireNonNull(range, "range"), today);
            case TODAY -> new DateRange(today, today);
            case YESTERDAY -> new DateRange(today.minusDays(1), today.minusDays(1));
            case THIS_WEEK -> week(today);
            case LAST_7_DAYS -> new DateRange(today.minusDays(6), today);
            case THIS_MONTH -> new DateRange(today.withDayOfMonth(1), today.with(TemporalAdjusters.lastDayOfMonth()));
            case LAST_30_DAYS -> new DateRange(today.minusDays(29), today);
            case LAST_12_WEEKS -> new DateRange(today.minusWeeks(11).with(DayOfWeek.MONDAY), today);
            case THIS_YEAR -> new DateRange(today.withDayOfYear(1), today.with(TemporalAdjusters.lastDayOfYear()));
        };
    }

    /**
     * The whole period before this one: all of last week, last month or last year,
     * even when the current period has only just begun.
     */
    public DateRange previous(Range range, LocalDate today) {
        Objects.requireNonNull(today, "today");
        DateRange current = resolve(range, today);
        if (this == THIS_MONTH || this == RANGE && range == Range.MONTH) {
            LocalDate previousFrom = current.from().minusMonths(1);
            return new DateRange(previousFrom, previousFrom.with(TemporalAdjusters.lastDayOfMonth()));
        }
        if (this == THIS_YEAR || this == RANGE && range == Range.YEAR) {
            LocalDate previousFrom = current.from().minusYears(1);
            return new DateRange(previousFrom, previousFrom.with(TemporalAdjusters.lastDayOfYear()));
        }
        long span = ChronoUnit.DAYS.between(current.from(), current.to()) + 1L;
        return new DateRange(current.from().minusDays(span), current.from().minusDays(1));
    }

    public static GroupBy autoBucket(Range range) {
        return switch (Objects.requireNonNull(range, "range")) {
            case DAY, WEEK, MONTH -> GroupBy.DAY;
            case YEAR -> GroupBy.MONTH;
        };
    }

    private static DateRange resolveRange(Range range, LocalDate today) {
        return switch (range) {
            case DAY -> new DateRange(today, today);
            case WEEK -> week(today);
            case MONTH -> new DateRange(today.withDayOfMonth(1), today.with(TemporalAdjusters.lastDayOfMonth()));
            case YEAR -> new DateRange(today.withDayOfYear(1), today.with(TemporalAdjusters.lastDayOfYear()));
        };
    }

    private static DateRange week(LocalDate day) {
        LocalDate monday = day.with(DayOfWeek.MONDAY);
        return new DateRange(monday, monday.plusDays(6));
    }
}
