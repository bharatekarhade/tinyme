package com.tinyme.tools.handlers;

import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.plans.model.PlanOccurrence;
import com.tinyme.domain.plans.model.RepeatLabel;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class PlanToolOutputs {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private PlanToolOutputs() { }

    static Map<String, Object> occurrence(PlanOccurrence occurrence) {
        Objects.requireNonNull(occurrence, "occurrence");
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", occurrence.id());
        if (occurrence.planId() != null) json.put("plan_id", occurrence.planId().toString());
        json.put("day", occurrence.day().toString());
        json.put("time", time(occurrence.startTime()));
        if (occurrence.endTime() != null) json.put("end_time", time(occurrence.endTime()));
        if (occurrence.endDay() != null) json.put("end_day", occurrence.endDay().toString());
        json.put("title", occurrence.title());
        json.put("place", occurrence.place());
        json.put("people", occurrence.people().stream().map(PlanToolOutputs::person).toList());
        json.put("status", occurrence.status().name().toLowerCase(Locale.ROOT));
        json.put("repeat_label", RepeatLabel.format(occurrence.repeat(), occurrence.repeatStartDay()));
        return json;
    }

    static List<Map<String, Object>> occurrences(List<PlanOccurrence> occurrences) {
        return occurrences.stream().map(PlanToolOutputs::occurrence).toList();
    }

    static String summaryTime(LocalDate day, LocalTime time) {
        String dayName = day.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
        return dayName + (time == null ? " all day" : " " + time.format(TIME).toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> person(PersonSnapshot person) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("slug", person.slug());
        value.put("display_name", person.displayName());
        value.put("relationship", person.relationship());
        return value;
    }

    private static String time(LocalTime time) {
        return time == null ? null : time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
    }

}
