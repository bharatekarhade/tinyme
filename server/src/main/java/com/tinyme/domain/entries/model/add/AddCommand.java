package com.tinyme.domain.entries.model.add;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AddCommand(
        String kind,
        BigDecimal quantity,
        String text,
        Map<String, Object> data,
        List<String> tags,
        Instant ts,
        ZoneId zone,
        String source
) {
    public AddCommand {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(source, "source");
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
