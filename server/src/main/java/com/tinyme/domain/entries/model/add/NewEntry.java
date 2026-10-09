package com.tinyme.domain.entries.model.add;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record NewEntry(
        String kind,
        BigDecimal quantity,
        String text,
        Map<String, Object> data,
        List<String> tags,
        Instant ts,
        LocalDate localDay,
        String source
) {
    public NewEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(localDay, "localDay");
        Objects.requireNonNull(source, "source");
        quantity = quantity == null ? BigDecimal.ONE : quantity;
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
