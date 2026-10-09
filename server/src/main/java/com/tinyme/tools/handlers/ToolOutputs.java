package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.EntrySnapshot;

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
}
