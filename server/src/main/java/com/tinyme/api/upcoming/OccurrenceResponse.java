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
                                 String notes,
                                 String place,
                                 List<PersonResponse> people,
                                 String status,
                                 @JsonProperty("repeat_label") String repeatLabel,
                                 String habit,
                                 boolean editable,
                                 @JsonProperty("gmail_message_id") String gmailMessageId,
                                 String origin,
                                 @JsonProperty("origin_url") String originUrl) {
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
                occurrence.notes(), occurrence.place(), occurrence.people().stream().map(OccurrenceResponse::person).toList(),
                occurrence.status().name().toLowerCase(Locale.ROOT),
                RepeatLabel.format(occurrence.repeat(), occurrence.repeatStartDay()),
                occurrence.habitKind(), !"people".equals(occurrence.source()),
                "chat".equals(occurrence.source()) && occurrence.externalId() != null
                        && occurrence.externalId().startsWith("gmail:")
                        ? occurrence.externalId().substring("gmail:".length()) : null,
                origin(occurrence.source(), occurrence.externalId()), originUrl(occurrence.source(), occurrence.externalId()));
    }

    /**
     * Where the plan came from, for the small logo beside it: gmail (found in an email), linkedin (a job alert), or
     * google_calendar. Null for what you made in TinyMe and for birthdays.
     */
    static String origin(String source, String externalId) {
        if ("google".equals(source)) return "google_calendar";
        if (!"chat".equals(source) || externalId == null) return null;
        if (externalId.startsWith("gmail:")) return "gmail";
        if (externalId.startsWith("linkedin:") || externalId.startsWith("job:")) return "linkedin";
        return null;
    }

    /** The LinkedIn posting a job task came from; email links are built by the app from gmail_message_id. */
    static String originUrl(String source, String externalId) {
        if (!"chat".equals(source) || externalId == null || !externalId.startsWith("linkedin:")) return null;
        String jobId = externalId.substring("linkedin:".length());
        return jobId.chars().allMatch(Character::isDigit) && !jobId.isEmpty()
                ? "https://www.linkedin.com/jobs/view/" + jobId + "/" : null;
    }

    private static PersonResponse person(PersonSnapshot person) {
        return new PersonResponse(person.slug(), person.displayName());
    }

    private static String time(LocalTime value) {
        return value == null ? null : value.format(TIME);
    }

    public record PersonResponse(String slug, String name) { }
}
