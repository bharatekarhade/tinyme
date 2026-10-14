package com.tinyme.domain.health.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/** One checked item from the phone: what becomes, or replaces, the entry with this external id. */
public record HealthItem(String externalId, String kind, LocalDate localDay, Instant ts, BigDecimal quantity,
                         Map<String, Object> data) {
    public HealthItem {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(localDay, "localDay");
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(quantity, "quantity");
        data = Map.copyOf(Objects.requireNonNull(data, "data"));
    }
}
