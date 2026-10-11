package com.tinyme.settings;

import com.tinyme.agent.repository.AgentSettingsRepository;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;

/** Resolves the owner's configured time zone, with the product default as a fallback. */
@Component
public class UserZone {
    private static final String DEFAULT_ZONE = "Asia/Tokyo";
    private final AgentSettingsRepository settings;

    public UserZone(AgentSettingsRepository settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public ZoneId current() {
        String configured = settings.userTimezone().orElse(DEFAULT_ZONE);
        try {
            return ZoneId.of(configured);
        } catch (DateTimeException invalidZone) {
            throw new IllegalStateException("The configured user.tz setting must be a valid time zone", invalidZone);
        }
    }
}
