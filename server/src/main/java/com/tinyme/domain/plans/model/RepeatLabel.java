package com.tinyme.domain.plans.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Shared natural-language label for a recurring plan in tools and API responses. */
public final class RepeatLabel {
    private RepeatLabel() { }

    public static String format(Repeat repeat, LocalDate startDay) {
        if (repeat == null) return null;
        Objects.requireNonNull(startDay, "startDay");
        return switch (repeat.freq()) {
            case DAILY -> interval("day", repeat.interval());
            case WEEKLY -> weekly(repeat, startDay);
            case MONTHLY -> interval("month", repeat.interval()) + " on the " + ordinal(startDay.getDayOfMonth());
            case YEARLY -> interval("year", repeat.interval()) + " on " + startDay.getMonth()
                    .getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + ordinal(startDay.getDayOfMonth());
        };
    }

    private static String weekly(Repeat repeat, LocalDate startDay) {
        Set<DayOfWeek> days = repeat.days().isEmpty() ? Set.of(startDay.getDayOfWeek()) : repeat.days();
        String names = days.stream()
                .map(day -> day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                .collect(Collectors.joining(", "));
        if (repeat.interval() == 1) return "Every " + names;
        return "Every " + repeat.interval() + " weeks on " + names;
    }

    private static String interval(String unit, int interval) {
        return interval == 1 ? "Every " + unit : "Every " + interval + " " + unit + "s";
    }

    private static String ordinal(int day) {
        int mod100 = day % 100;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return day + suffix;
    }
}
