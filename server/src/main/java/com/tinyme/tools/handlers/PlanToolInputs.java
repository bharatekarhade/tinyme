package com.tinyme.tools.handlers;

import com.tinyme.domain.plans.model.Repeat;
import com.tinyme.domain.plans.model.RepeatFrequency;
import com.tinyme.domain.people.model.get.PeopleLookup;
import com.tinyme.domain.people.model.get.PersonMatchType;
import com.tinyme.domain.people.service.PersonService;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;

final class PlanToolInputs {
    private PlanToolInputs() { }

    static LocalTime optionalTime(JsonNode input, String key) {
        String raw = ToolInputs.optionalString(input, key);
        if (raw == null) return null;
        try {
            if (!raw.matches("^[0-2][0-9]:[0-5][0-9]$")) throw new DateTimeParseException("time", raw, 0);
            return LocalTime.parse(raw);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(key + " must use HH:mm (24-hour time)");
        }
    }

    static Repeat optionalRepeat(JsonNode input) {
        JsonNode value = input.get("repeat");
        if (value == null || value.isNull()) return null;
        if (!value.isObject()) throw new IllegalArgumentException("repeat must be an object");
        JsonNode freqNode = value.get("freq");
        if (freqNode == null || !freqNode.isString() || freqNode.stringValue().isBlank()) {
            throw new IllegalArgumentException("repeat.freq is required");
        }
        RepeatFrequency frequency;
        try {
            frequency = RepeatFrequency.valueOf(freqNode.stringValue().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("repeat.freq must be one of daily, weekly, monthly or yearly");
        }
        JsonNode intervalNode = value.get("interval");
        Integer interval = intervalNode == null || intervalNode.isNull() ? null : ToolInputs.optionalInt(value, "interval");
        List<String> dayNames = ToolInputs.optionalStringList(value, "days");
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (dayNames != null) {
            for (String day : dayNames) {
                DayOfWeek parsed = switch (day.strip().toLowerCase(Locale.ROOT)) {
                    case "mon" -> DayOfWeek.MONDAY;
                    case "tue" -> DayOfWeek.TUESDAY;
                    case "wed" -> DayOfWeek.WEDNESDAY;
                    case "thu" -> DayOfWeek.THURSDAY;
                    case "fri" -> DayOfWeek.FRIDAY;
                    case "sat" -> DayOfWeek.SATURDAY;
                    case "sun" -> DayOfWeek.SUNDAY;
                    default -> throw new IllegalArgumentException("repeat.days must contain mon, tue, wed, thu, fri, sat or sun");
                };
                if (!days.add(parsed)) throw new IllegalArgumentException("repeat.days must not contain duplicates");
            }
        }
        LocalDate until = ToolInputs.optionalDate(value, "until");
        return new Repeat(frequency, interval == null ? 1 : interval, days, until);
    }

    static List<String> resolvePeople(List<String> names, PersonService people) {
        List<String> slugs = new ArrayList<>();
        for (String rawName : names) {
            String name = rawName.strip();
            if (name.isBlank()) throw new IllegalArgumentException("people must not contain blank names");
            PeopleLookup lookup = people.get(name);
            if (lookup.match() != PersonMatchType.EXACT || lookup.matches().isEmpty()) {
                throw new IllegalArgumentException("No person named " + name + "; call people_upsert first");
            }
            if (lookup.matches().size() > 1) {
                throw new IllegalArgumentException("More than one person matches " + name + "; ask which one they mean");
            }
            slugs.add(lookup.matches().getFirst().person().slug());
        }
        return List.copyOf(slugs);
    }
}
