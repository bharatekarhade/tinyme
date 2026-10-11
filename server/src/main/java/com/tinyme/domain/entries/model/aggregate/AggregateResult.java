package com.tinyme.domain.entries.model.aggregate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AggregateResult(
        List<String> kinds,
        List<String> unknownKinds,
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
        kinds = List.copyOf(kinds);
        unknownKinds = List.copyOf(unknownKinds);
        where = Collections.unmodifiableMap(new LinkedHashMap<>(where));
        buckets = List.copyOf(buckets);
    }

}
