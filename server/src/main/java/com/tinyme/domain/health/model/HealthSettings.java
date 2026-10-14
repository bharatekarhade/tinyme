package com.tinyme.domain.health.model;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** What the phone has connected, whether it saves to Health, and which follow-up topics are off. */
public record HealthSettings(Set<HealthGroup> groups, boolean writeEnabled, Set<HealthNudgeTopic> nudgesOff) {
    public HealthSettings {
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(nudgesOff, "nudgesOff");
        groups = groups.isEmpty() ? EnumSet.noneOf(HealthGroup.class) : EnumSet.copyOf(groups);
        nudgesOff = nudgesOff.isEmpty() ? EnumSet.noneOf(HealthNudgeTopic.class) : EnumSet.copyOf(nudgesOff);
    }

    public static HealthSettings none() {
        return new HealthSettings(Set.of(), false, Set.of());
    }
}
