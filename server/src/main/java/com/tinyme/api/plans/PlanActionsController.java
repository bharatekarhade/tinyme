package com.tinyme.api.plans;

import com.tinyme.api.upcoming.OccurrenceResponse;
import com.tinyme.domain.plans.model.PlanChange;
import com.tinyme.domain.plans.service.PlanService;
import com.tinyme.domain.plans.service.PlanStateConflictException;
import com.tinyme.settings.UserZone;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

@RestController
@RequestMapping("/plans")
public class PlanActionsController {
    private final PlanService plans;
    private final UserZone userZone;
    private final Clock clock;

    public PlanActionsController(PlanService plans, UserZone userZone, Clock clock) {
        this.plans = plans;
        this.userZone = userZone;
        this.clock = clock;
    }

    @PostMapping("/{id}/occurrences/{day}/done")
    public OccurrenceResponse done(@PathVariable UUID id, @PathVariable LocalDate day) {
        return change(id, day, PlanService.QuickAction.DONE);
    }

    @DeleteMapping("/{id}/occurrences/{day}/done")
    public OccurrenceResponse undoDone(@PathVariable UUID id, @PathVariable LocalDate day) {
        return change(id, day, PlanService.QuickAction.UNDO_DONE);
    }

    @PostMapping("/{id}/occurrences/{day}/skip")
    public OccurrenceResponse skip(@PathVariable UUID id, @PathVariable LocalDate day) {
        return change(id, day, PlanService.QuickAction.SKIP);
    }

    @PostMapping("/{id}/occurrences/{day}/move")
    public OccurrenceResponse moveOccurrence(@PathVariable UUID id, @PathVariable LocalDate day,
                                             @RequestBody MoveRequest request) {
        try {
            return plans.moveOccurrence(id, day, request.day(), request.time(), userZone.current(), clock.instant())
                    .map(OccurrenceResponse::from)
                    .orElseThrow(PlanActionsController::notFound);
        } catch (PlanStateConflictException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    /**
     * Changes the time of every occurrence. Without a day the start day stays, so a series keeps its past days
     * and its weekdays; a day is only sent to move a one-off plan.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<Void> moveSeries(@PathVariable UUID id, @RequestBody EditScheduleRequest request) {
        Set<String> fields = request.day() == null
                ? Set.of("time", "end_time")
                : Set.of("day", "time", "end_time", "end_day");
        PlanChange.Edit change = new PlanChange.Edit(fields, null, request.day(), request.time(),
                request.endTime(), request.endDay(), null, List.of(), null, null, null, null, null);
        try {
            plans.update(id, change, userZone.current(), clock.instant()).orElseThrow(PlanActionsController::notFound);
            return ResponseEntity.noContent().build();
        } catch (PlanStateConflictException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!plans.delete(id, clock.instant())) throw notFound();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable UUID id) {
        if (!plans.restore(id)) throw notFound();
        return ResponseEntity.noContent().build();
    }

    private OccurrenceResponse change(UUID id, LocalDate day, PlanService.QuickAction action) {
        try {
            return plans.quickAction(id, day, action, userZone.current(), clock.instant())
                    .map(OccurrenceResponse::from)
                    .orElseThrow(PlanActionsController::notFound);
        } catch (PlanStateConflictException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found");
    }

    public record MoveRequest(LocalDate day, LocalTime time) {
        public MoveRequest {
            if (day == null || time == null) throw new IllegalArgumentException("day and time are required");
        }
    }

    public record EditScheduleRequest(LocalDate day, LocalTime time,
                                     @JsonProperty("end_time") LocalTime endTime,
                                     @JsonProperty("end_day") LocalDate endDay) {
        public EditScheduleRequest {
            if (time == null) throw new IllegalArgumentException("time is required");
        }
    }
}
