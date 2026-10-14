package com.tinyme.domain.health.model;

import java.util.Arrays;
import java.util.Optional;

/** Follow-up question topics that can be switched off. Keys match health_settings.nudges_off. */
public enum HealthNudgeTopic {
    WORKOUTS("workouts"),
    SLEEP("sleep"),
    WEIGHT("weight"),
    STEPS("steps");

    private final String key;

    HealthNudgeTopic(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<HealthNudgeTopic> fromKey(String key) {
        return Arrays.stream(values()).filter(topic -> topic.key.equals(key)).findFirst();
    }
}
