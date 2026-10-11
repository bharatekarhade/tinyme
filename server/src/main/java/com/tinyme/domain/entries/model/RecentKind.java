package com.tinyme.domain.entries.model;

import java.time.LocalDate;
import java.util.Objects;

/** A canonical entry kind used recently, with activity measured from live entries. */
public record RecentKind(String kind, long count, LocalDate lastLoggedOn) {
    public RecentKind {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(lastLoggedOn, "lastLoggedOn");
        if (count < 1) throw new IllegalArgumentException("count must be positive");
    }
}
