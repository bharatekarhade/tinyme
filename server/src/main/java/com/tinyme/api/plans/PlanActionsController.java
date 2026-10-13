package com.tinyme.api.plans;

import com.tinyme.api.upcoming.OccurrenceResponse;
import com.tinyme.domain.plans.service.PlanService;
import com.tinyme.domain.plans.service.PlanStateConflictException;
import com.tinyme.settings.UserZone;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!plans.delete(id, clock.instant())) throw notFound();
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
}
