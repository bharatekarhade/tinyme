package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.people.model.get.PersonMatch;

import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ToolOutputs {
    private ToolOutputs() {
    }

    static Map<String, Object> entry(EntrySnapshot entry, ZoneId zone) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(zone, "zone");

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", entry.id().toString());
        json.put("ts", OffsetDateTime.ofInstant(entry.ts().truncatedTo(ChronoUnit.SECONDS), zone)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        json.put("local_day", entry.localDay().toString());
        json.put("kind", entry.kind());
        json.put("quantity", entry.quantity());
        json.put("text", entry.text());
        json.put("data", entry.data());
        return json;
    }

    static Map<String, Object> person(PersonSnapshot person) {
        Objects.requireNonNull(person, "person");
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("slug", person.slug());
        json.put("display_name", person.displayName());
        json.put("aliases", person.aliases());
        json.put("relationship", person.relationship());
        json.put("memory_path", person.memoryPath());
        return json;
    }

    static Map<String, Object> personMatch(PersonMatch match, ZoneId zone) {
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(zone, "zone");
        Map<String, Object> json = person(match.person());
        json.put("last_seen", match.lastSeen() == null ? null : OffsetDateTime
                .ofInstant(match.lastSeen().truncatedTo(ChronoUnit.SECONDS), zone)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return json;
    }
}
