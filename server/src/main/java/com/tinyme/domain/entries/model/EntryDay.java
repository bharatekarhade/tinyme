package com.tinyme.domain.entries.model;

import java.time.LocalDate;
import java.util.Objects;

public record EntryDay(String kind, LocalDate day) {
    public EntryDay {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(day, "day");
    }
}
