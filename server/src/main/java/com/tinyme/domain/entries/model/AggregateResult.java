package com.tinyme.domain.entries.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record AggregateResult(
        String kind,
        boolean kindKnown,
        AggregateMetric metric,
        String field,
        Map<String, Object> where,
        LocalDate from,
        LocalDate to,
        GroupBy groupBy,
        BigDecimal value,
        Long entries,
        List<Bucket> buckets
) {
    public AggregateResult {
        where = Collections.unmodifiableMap(new LinkedHashMap<>(where));
        buckets = List.copyOf(buckets);
    }
}
