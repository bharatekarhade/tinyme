package com.tinyme.api.upcoming;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.plans.model.PlanOccurrence;
import com.tinyme.domain.plans.model.RepeatLabel;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record OccurrenceResponse(String id,
                                 @JsonProperty("plan_id") String planId,
                                 String source,
                                 String kind,
                                 String title,
                                 String day,
                                 @JsonProperty("start_time") String startTime,
                                 @JsonProperty("end_time") String endTime,
                                 @JsonProperty("starts_at") String startsAt,
                                 @JsonProperty("ends_at") String endsAt,
                                 @JsonProperty("all_day") boolean allDay,
                                 String place,
                                 List<PersonResponse> people,
                                 String status,
                                 @JsonProperty("repeat_label") String repeatLabel,
                                 String habit,
                                 boolean editable) {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    public OccurrenceResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(day, "day");
        people = List.copyOf(Objects.requireNonNull(people, "people"));
        Objects.requireNonNull(status, "status");
    }

    public static OccurrenceResponse from(PlanOccurrence occurrence) {
        Objects.requireNonNull(occurrence, "occurrence");
        return new OccurrenceResponse(occurrence.id(),
                occurrence.planId() == null ? null : occurrence.planId().toString(),
                occurrence.source(), occurrence.kind().name().toLowerCase(Locale.ROOT), occurrence.title(),
                occurrence.day().toString(), time(occurrence.startTime()), time(occurrence.endTime()),
                occurrence.startsAt() == null ? null : occurrence.startsAt().toString(),
                occurrence.endsAt() == null ? null : occurrence.endsAt().toString(), occurrence.allDay(),
                occurrence.place(), occurrence.people().stream().map(OccurrenceResponse::person).toList(),
                occurrence.status().name().toLowerCase(Locale.ROOT),
                RepeatLabel.format(occurrence.repeat(), occurrence.repeatStartDay()),
                occurrence.habitKind(), !"people".equals(occurrence.source()));
    }

    private static PersonResponse person(PersonSnapshot person) {
        return new PersonResponse(person.slug(), person.displayName());
    }

    private static String time(LocalTime value) {
        return value == null ? null : value.format(TIME);
    }

    public record PersonResponse(String slug, String name) { }
}
