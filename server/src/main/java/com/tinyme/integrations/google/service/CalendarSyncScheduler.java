package com.tinyme.integrations.google.service;

import com.tinyme.domain.plans.service.PlanChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Coalesces plan edits for ten seconds and also runs a five-minute safety sync. */
@Component
public class CalendarSyncScheduler {
    private static final Duration DEBOUNCE = Duration.ofSeconds(10);
    private static final Duration PERIODIC = Duration.ofMinutes(5);
    private final CalendarSyncService sync;
    private final Clock clock;
    private final AtomicReference<Instant> requestedAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastSyncAt = new AtomicReference<>(Instant.EPOCH);
    private final AtomicBoolean running = new AtomicBoolean();

    public CalendarSyncScheduler(CalendarSyncService sync, Clock clock) {
        this.sync = sync;
        this.clock = clock;
    }

    @EventListener
    public void planChanged(PlanChangedEvent ignored) {
        requestDebounced();
    }

    public void requestDebounced() {
        requestedAt.set(clock.instant());
    }

    /** Foreground sync is queued for the next scheduler tick. */
    public void requestNow() {
        requestedAt.set(clock.instant().minus(DEBOUNCE));
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        Instant now = clock.instant();
        Instant request = requestedAt.get();
        boolean requestedDue = request != null && !request.plus(DEBOUNCE).isAfter(now);
        boolean periodicDue = !lastSyncAt.get().plus(PERIODIC).isAfter(now);
        if ((!requestedDue && !periodicDue) || !running.compareAndSet(false, true)) return;
        if (requestedDue) requestedAt.compareAndSet(request, null);
        try {
            sync.synchronize();
            lastSyncAt.set(clock.instant());
        } finally {
            running.set(false);
        }
    }
}
