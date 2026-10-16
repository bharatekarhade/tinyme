package com.tinyme.integrations.google.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runs at most once per configured interval, with a coalesced foreground request. */
@Component
public class MailScanScheduler {
    private static final Logger log = LoggerFactory.getLogger(MailScanScheduler.class);
    private final MailScanService scan;
    private final Clock clock;
    private final Duration interval;
    private final AtomicReference<Instant> lastRun = new AtomicReference<>(Instant.EPOCH);
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    public MailScanScheduler(MailScanService scan, Clock clock,
                             @Value("${tinyme.mail-scan.interval:15m}") Duration interval) {
        this.scan = scan;
        this.clock = clock;
        this.interval = interval;
    }

    public void requestNow() { requested.set(true); }

    @Scheduled(fixedDelay = 60_000)
    public void tick() {
        Instant now = clock.instant();
        if (!requested.get() && lastRun.get().plus(interval).isAfter(now)) return;
        if (!running.compareAndSet(false, true)) return;
        requested.set(false);
        lastRun.set(now);
        try {
            MailScanService.RunResult result = scan.run();
            log.info("Mail scan finished status={} scanned={} suggested={} rejected={}",
                    result.status(), result.scanned(), result.suggested(), result.rejected());
        } catch (RuntimeException failure) {
            log.warn("Mail scan scheduler failed ({})", failure.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }
}
