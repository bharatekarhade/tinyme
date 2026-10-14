package com.tinyme.domain.health.model;

import java.util.Arrays;
import java.util.Optional;

/** The switches on the phone's Apple Health screen. Keys match health_settings.groups. */
public enum HealthGroup {
    ACTIVITY("activity"),
    WORKOUTS("workouts"),
    BODY("body"),
    SLEEP_HEART("sleep_heart");

    private final String key;

    HealthGroup(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<HealthGroup> fromKey(String key) {
        return Arrays.stream(values()).filter(group -> group.key.equals(key)).findFirst();
    }
}
