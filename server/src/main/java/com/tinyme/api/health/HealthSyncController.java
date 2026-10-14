package com.tinyme.api.health;

import com.tinyme.domain.health.model.HealthSyncResult;
import com.tinyme.domain.health.model.InvalidHealthItemException;
import com.tinyme.domain.health.service.HealthSyncService;
import com.tinyme.maintenance.MaintenanceGate;
import com.tinyme.settings.UserZone;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.time.Clock;

/** The phone sends what it read from Apple Health; the server never talks to Apple itself. */
@RestController
@RequestMapping("/health")
public class HealthSyncController {
    private final HealthSyncService sync;
    private final MaintenanceGate gate;
    private final UserZone userZone;
    private final Clock clock;

    public HealthSyncController(HealthSyncService sync, MaintenanceGate gate, UserZone userZone, Clock clock) {
        this.sync = sync;
        this.gate = gate;
        this.userZone = userZone;
        this.clock = clock;
    }

    /** 400 carries "index" (or "deleted_index") when one item is wrong, so the phone can drop it and send the rest. */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(@RequestBody JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body must be an object with items and deleted");
        }
        // Delete all waits for running syncs and turns new ones away, like chat turns.
        try (var permit = gate.enterTurn()) {
            HealthSyncResult result = sync.sync(body.path("items"), body.path("deleted"), userZone.current(), clock.instant());
            return ResponseEntity.ok(new HealthSyncResponse(result.upserted(), result.unchanged(), result.deleted(),
                    result.merged()));
        } catch (MaintenanceGate.MaintenanceInProgressException resetting) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, resetting.getMessage(), resetting);
        } catch (InvalidHealthItemException invalid) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, invalid.getMessage());
            if (invalid.index() != null) problem.setProperty(invalid.part().indexProperty(), invalid.index());
            return ResponseEntity.badRequest().body(problem);
        }
    }

    public record HealthSyncResponse(int upserted, int unchanged, int deleted, int merged) { }
}
