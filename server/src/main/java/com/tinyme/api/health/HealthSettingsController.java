package com.tinyme.api.health;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tinyme.domain.health.model.HealthGroup;
import com.tinyme.domain.health.model.HealthNudgeTopic;
import com.tinyme.domain.health.model.HealthSettings;
import com.tinyme.domain.health.repository.HealthSettingsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The phone's Apple Health switches, kept on the server so the agent knows what is connected. */
@RestController
@RequestMapping("/health")
public class HealthSettingsController {
    private final HealthSettingsRepository settings;
    private final Clock clock;

    public HealthSettingsController(HealthSettingsRepository settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @PutMapping("/settings")
    public HealthSettingsBody put(@RequestBody HealthSettingsBody body) {
        if (body == null || body.groups() == null || body.writeEnabled() == null || body.nudgesOff() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groups, write_enabled and nudges_off are required");
        }
        Set<HealthGroup> groups = EnumSet.noneOf(HealthGroup.class);
        for (String key : body.groups()) {
            groups.add(HealthGroup.fromKey(key).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "groups: unknown group " + key + "; use activity, workouts, body or sleep_heart")));
        }
        Set<HealthNudgeTopic> nudgesOff = EnumSet.noneOf(HealthNudgeTopic.class);
        for (String key : body.nudgesOff()) {
            nudgesOff.add(HealthNudgeTopic.fromKey(key).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "nudges_off: unknown topic " + key + "; use workouts, sleep, weight or steps")));
        }
        var saved = new HealthSettings(groups, body.writeEnabled(), nudgesOff);
        settings.save(saved, clock.instant());
        return HealthSettingsBody.of(saved);
    }

    public record HealthSettingsBody(List<String> groups,
                                     @JsonProperty("write_enabled") Boolean writeEnabled,
                                     @JsonProperty("nudges_off") List<String> nudgesOff) {
        static HealthSettingsBody of(HealthSettings settings) {
            return new HealthSettingsBody(settings.groups().stream().map(HealthGroup::key).toList(),
                    settings.writeEnabled(), settings.nudgesOff().stream().map(HealthNudgeTopic::key).toList());
        }
    }
}
