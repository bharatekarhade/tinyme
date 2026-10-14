package com.tinyme.api.health;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tinyme.domain.health.model.HealthGroup;
import com.tinyme.domain.health.repository.HealthEntryRepository;
import com.tinyme.domain.health.repository.HealthSettingsRepository;
import com.tinyme.domain.health.repository.HealthSyncEpochRepository;
import com.tinyme.domain.health.service.HealthDisconnect;
import com.tinyme.maintenance.MaintenanceGate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Settings → Apple Health: what's stored per kind, and Disconnect. */
@RestController
@RequestMapping("/health")
public class HealthStatusController {
    static final String CONFIRMATION = "DELETE HEALTH DATA";

    private final HealthSettingsRepository settings;
    private final HealthEntryRepository entries;
    private final HealthSyncEpochRepository syncEpoch;
    private final HealthDisconnect disconnect;
    private final MaintenanceGate gate;
    private final Clock clock;

    public HealthStatusController(HealthSettingsRepository settings, HealthEntryRepository entries,
                                  HealthSyncEpochRepository syncEpoch,
                                  HealthDisconnect disconnect, MaintenanceGate gate, Clock clock) {
        this.settings = settings;
        this.entries = entries;
        this.syncEpoch = syncEpoch;
        this.disconnect = disconnect;
        this.gate = gate;
        this.clock = clock;
    }

    @GetMapping("/status")
    public StatusBody status() {
        var stored = settings.load();
        return new StatusBody(stored.groups().stream().map(HealthGroup::key).toList(), false, syncEpoch.current(),
                entries.syncLog().stream().map(row -> new KindStatus(row.kind(), row.lastSyncAt(), row.lastDay(),
                        row.itemsTotal())).toList());
    }

    /** "Disconnect and remove imported Health data". Chat workouts a Watch one had absorbed come back. */
    @DeleteMapping("/data")
    public DisconnectBody delete(@RequestBody ConfirmBody body) {
        if (body == null || !CONFIRMATION.equals(body.confirm())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirm must exactly match " + CONFIRMATION);
        }
        try (var permit = gate.enterTurn()) {
            var result = disconnect.removeImportedData(clock.instant());
            return new DisconnectBody(result.removed(), result.restored());
        } catch (MaintenanceGate.MaintenanceInProgressException resetting) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, resetting.getMessage(), resetting);
        }
    }

    public record ConfirmBody(String confirm) { }

    public record StatusBody(List<String> groups, @JsonProperty("write_enabled") boolean writeEnabled,
                             @JsonProperty("sync_epoch") long syncEpoch,
                             List<KindStatus> kinds) { }

    public record KindStatus(String kind, @JsonProperty("last_sync_at") Instant lastSyncAt,
                             @JsonProperty("last_day") LocalDate lastDay, @JsonProperty("items_total") long itemsTotal) { }

    public record DisconnectBody(int removed, @JsonProperty("restored_chat_workouts") int restoredChatWorkouts) { }
}
