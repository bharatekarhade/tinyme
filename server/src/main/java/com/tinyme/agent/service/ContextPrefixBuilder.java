package com.tinyme.agent.service;

import com.tinyme.domain.entries.repository.EntryKindRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

@Component
public class ContextPrefixBuilder {
    private static final DateTimeFormatter LOCAL_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd EEEE HH:mm:ss XXX", Locale.ENGLISH);
    private static final int KIND_LIMIT = 30;

    private final EntryKindRepository entryKinds;
    private final Clock clock;

    public ContextPrefixBuilder(EntryKindRepository entryKinds, Clock clock) {
        this.entryKinds = entryKinds;
        this.clock = clock;
    }

    public String build(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        var lines = new ArrayList<String>();
        lines.add("[context]");
        lines.add("now: " + ZonedDateTime.now(clock.withZone(zone)).format(LOCAL_TIME));
        lines.add("tz: " + zone.getId());
        var kinds = entryKinds.topKinds(KIND_LIMIT);
        if (!kinds.isEmpty()) {
            lines.add("known_kinds: " + String.join(", ", kinds));
        }
        lines.add("[/context]");
        return String.join("\n", lines);
    }
}
