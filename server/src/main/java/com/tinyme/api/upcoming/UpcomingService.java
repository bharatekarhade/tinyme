package com.tinyme.api.upcoming;

import com.tinyme.domain.plans.model.OccurrenceStatus;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.PlanOccurrence;
import com.tinyme.domain.plans.service.PlanCalendar;
import com.tinyme.domain.plans.service.PlanService;
import com.tinyme.settings.UserZone;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class UpcomingService {
    private final PlanService plans;
    private final UserZone userZone;
    private final Clock clock;

    public UpcomingService(PlanService plans, UserZone userZone, Clock clock) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.userZone = Objects.requireNonNull(userZone, "userZone");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(readOnly = true)
    public UpcomingResponse get(LocalDate requestedFrom, LocalDate requestedTo) {
        Instant now = clock.instant();
        var zone = userZone.current();
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate from = requestedFrom == null ? today : requestedFrom;
        LocalDate to = requestedTo == null ? from.plusDays(6) : requestedTo;
        List<PlanOccurrence> items = plans.upcomingItems(from, to, zone, now);
        List<PlanOccurrence> rangedItems = items.stream()
                .filter(item -> !item.day().isBefore(from) && !item.day().isAfter(to))
                .toList();

        List<UpcomingResponse.DaySummary> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            LocalDate currentDay = day;
            List<PlanOccurrence> onDay = rangedItems.stream()
                    .filter(item -> item.day().equals(currentDay)).toList();
            days.add(new UpcomingResponse.DaySummary(currentDay.toString(), onDay.size(),
                    onDay.stream().anyMatch(item -> item.startsAt() != null)));
        }

        List<PlanOccurrence> nextUpItems = plans.upcomingItems(today, today.plusDays(1), zone, now);
        List<PlanOccurrence> timedEvents = nextUpItems.stream()
                .filter(item -> item.kind() == PlanKind.EVENT && item.startsAt() != null)
                .sorted(Comparator.comparing(PlanOccurrence::startsAt)).toList();
        PlanOccurrence nextUp = timedEvents.stream()
                .filter(item -> item.status() == OccurrenceStatus.IN_PROGRESS)
                .findFirst().orElseGet(() -> timedEvents.stream()
                        .filter(item -> item.status() == OccurrenceStatus.UPCOMING
                                && !item.startsAt().isBefore(now)
                                && !item.startsAt().isAfter(now.plus(24, ChronoUnit.HOURS)))
                        .findFirst().orElse(null));

        List<PlanOccurrence> overdue = today.isBefore(from) || today.isAfter(to) ? List.of() : items.stream()
                .filter(item -> item.kind() == PlanKind.TASK && item.day().isBefore(today)
                        && item.status() == OccurrenceStatus.MISSED).toList();
        return new UpcomingResponse(now, today, from, to, days,
                nextUp == null ? null : OccurrenceResponse.from(nextUp),
                overdue.stream().map(OccurrenceResponse::from).toList(),
                rangedItems.stream().map(OccurrenceResponse::from).toList());
    }
}
