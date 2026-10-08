package com.tinyme.agent.service;

import com.tinyme.domain.entries.model.TodayTotal;
import com.tinyme.domain.entries.repository.EntryKindRepository;
import com.tinyme.domain.entries.repository.EntryRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
public class ContextPrefixBuilder {
    private static final DateTimeFormatter LOCAL_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd EEEE HH:mm:ss XXX", Locale.ENGLISH);
    private static final int KIND_LIMIT = 30;

    private final EntryKindRepository entryKinds;
    private final EntryRepository entries;
    private final Clock clock;

    public ContextPrefixBuilder(EntryKindRepository entryKinds, EntryRepository entries, Clock clock) {
        this.entryKinds = entryKinds;
        this.entries = entries;
        this.clock = clock;
    }

    /**
     * This is context builder when sending message to the agnet.
     * [context]
     * now: 2026-10-07 Wednesday 12:29:41 +09:00
     * tz: Asia/Tokyo
     * known_kinds: drink, meal, sleep
     * [/context]
     * @param zone
     * @return String
     */
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
        LocalDate today = LocalDate.now(clock.withZone(zone));
        List<TodayTotal> totals = entries.todayTotals(today);
        if (!totals.isEmpty()) {
            lines.add("today: " + totals.stream().map(ContextPrefixBuilder::formatTotal)
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        lines.add("[/context]");
        return String.join("\n", lines);
    }

    private static String formatTotal(TodayTotal total) {
        String label = total.kind();
        if (total.type() != null) {
            label += " (" + total.type() + ")";
        }
        BigDecimal quantity = total.quantity().stripTrailingZeros();
        return label + "=" + quantity.toPlainString();
    }
}
