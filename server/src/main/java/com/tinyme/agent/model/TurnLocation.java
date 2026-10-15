package com.tinyme.agent.model;

import java.time.Instant;
import java.util.Objects;

/** A recent, accurate location fix supplied by the phone for this turn only. */
public record TurnLocation(double lat, double lng, double accuracyM, Instant at, String area) {
    public TurnLocation {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
            throw new IllegalArgumentException("lat must be between -90 and 90");
        }
        if (!Double.isFinite(lng) || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("lng must be between -180 and 180");
        }
        if (!Double.isFinite(accuracyM) || accuracyM < 0 || accuracyM >= 500) {
            throw new IllegalArgumentException("accuracyM must be between 0 and 500 (exclusive)");
        }
        Objects.requireNonNull(at, "at");
        area = cleanArea(area);
    }

    private static String cleanArea(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.strip().replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ");
        return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120).strip();
    }
}
