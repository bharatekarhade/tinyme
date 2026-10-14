package com.tinyme.agent.service;

import com.tinyme.domain.entries.model.aggregate.TodayTotal;
import com.tinyme.domain.entries.repository.EntryKindRepository;
import com.tinyme.domain.entries.repository.EntryRepository;
import com.tinyme.domain.health.service.HealthContext;
import com.tinyme.domain.health.service.HealthNudges;
import com.tinyme.domain.plans.service.PlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
    private static final Logger LOG = LoggerFactory.getLogger(ContextPrefixBuilder.class);
    private static final DateTimeFormatter LOCAL_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd EEEE HH:mm:ss XXX", Locale.ENGLISH);
    private static final int KIND_LIMIT = 30;

    private final EntryKindRepository entryKinds;
    private final EntryRepository entries;
    private final Clock clock;
    private final PlanService plans;
    private final HealthContext health;
    private final HealthNudges nudges;

    public ContextPrefixBuilder(EntryKindRepository entryKinds, EntryRepository entries, PlanService plans,
                                HealthContext health, HealthNudges nudges, Clock clock) {
        this.entryKinds = entryKinds;
        this.entries = entries;
        this.plans = plans;
        this.health = health;
        this.nudges = nudges;
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
        return build(zone, clock.instant());
    }

    /** Builds the context as of when the user sent the message, which may predate delivery while offline. */
    public String build(ZoneId zone, Instant referenceTime) {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(referenceTime, "referenceTime");
        var lines = new ArrayList<String>();
        lines.add("[context]");
        lines.add("now: " + ZonedDateTime.ofInstant(referenceTime, zone).format(LOCAL_TIME));
        lines.add("tz: " + zone.getId());
        var kinds = entryKinds.topKinds(KIND_LIMIT);
        if (!kinds.isEmpty()) {
            lines.add("known_kinds: " + String.join(", ", kinds));
        }
        LocalDate today = LocalDate.ofInstant(referenceTime, zone);
        List<TodayTotal> totals = entries.todayTotals(today);
        if (!totals.isEmpty()) {
            lines.add("today: " + totals.stream().map(ContextPrefixBuilder::formatTotal)
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        try {
            String upcoming = plans.contextLine(zone, referenceTime);
            if (upcoming != null && !upcoming.isBlank()) lines.add(upcoming);
        } catch (RuntimeException error) {
            LOG.warn("Unable to build upcoming plan context; omitting it", error);
        }
        try {
            String healthLine = health.line(zone, referenceTime);
            if (healthLine != null) lines.add(healthLine);
        } catch (RuntimeException error) {
            LOG.warn("Unable to build Health context; omitting it", error);
        }
        try {
            nudges.pick(zone, referenceTime).ifPresent(nudge -> lines.add("nudge: " + nudge.text()));
        } catch (RuntimeException error) {
            LOG.warn("Unable to pick a follow-up question; omitting it", error);
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
