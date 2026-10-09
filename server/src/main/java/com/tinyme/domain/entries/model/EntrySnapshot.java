package com.tinyme.domain.entries.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record EntrySnapshot(
        UUID id,
        Instant ts,
        LocalDate localDay,
        String kind,
        BigDecimal quantity,
        String text,
        Map<String, Object> data
) {
    public EntrySnapshot {
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
