package com.tinyme.domain.cards.model;

import com.tinyme.domain.entries.model.aggregate.AggregateQuery;
import com.tinyme.domain.entries.model.aggregate.AggregateMetric;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record MetricSpec(List<String> kinds, AggregateMetric op, String field,
                         Map<String, Object> where) {
    public MetricSpec {
        kinds = List.copyOf(AggregateQuery.validateKinds(kinds));
        Objects.requireNonNull(op, "metric.op is required");
        if (op.requiresField() && (field == null || field.isBlank())) {
            throw new IllegalArgumentException("metric.field is required for " + op.name().toLowerCase());
        }
        if (field != null && !field.matches("^[a-z][a-z0-9_]{0,31}$")) {
            throw new IllegalArgumentException("metric.field must match ^[a-z][a-z0-9_]{0,31}$");
        }
        where = where == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(where));
    }
}
