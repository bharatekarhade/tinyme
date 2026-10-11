package com.tinyme.domain.entries.model.aggregate;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record BreakdownQuery(List<String> kinds, AggregateMetric metric, String field,
                             Map<String, Object> where, LocalDate from, LocalDate to,
                             String by, int top) {
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    public BreakdownQuery {
        kinds = List.copyOf(AggregateQuery.validateKinds(kinds));
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(by, "by");
        where = where == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(where));
        if (metric != AggregateMetric.COUNT && metric != AggregateMetric.SUM) {
            throw new IllegalArgumentException("breakdown metric must be count or sum");
        }
        if (metric.requiresField() && (field == null || !KEY_PATTERN.matcher(field).matches())) {
            throw new IllegalArgumentException("field is required for metric " + metric.name().toLowerCase());
        }
        if (!KEY_PATTERN.matcher(by).matches()) throw new IllegalArgumentException("by must be a data key");
        if (top < 1 || top > 5) throw new IllegalArgumentException("top must be between 1 and 5");
        if (from != null && to != null && to.isBefore(from)) throw new IllegalArgumentException("to must be on or after from");
    }

    public boolean allKinds() {
        return kinds.equals(List.of("*"));
    }
}
