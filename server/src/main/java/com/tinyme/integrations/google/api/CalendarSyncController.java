package com.tinyme.integrations.google.api;

import com.tinyme.integrations.google.service.CalendarSyncScheduler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Queues a foreground sync; the scheduled worker coalesces it with other recent plan changes. */
@RestController
@RequestMapping("/integrations/google/sync")
public class CalendarSyncController {
    private final CalendarSyncScheduler scheduler;

    public CalendarSyncController(CalendarSyncScheduler scheduler) { this.scheduler = scheduler; }

    @PostMapping
    public ResponseEntity<Void> requestSync() {
        scheduler.requestNow();
        return ResponseEntity.accepted().build();
    }
}
