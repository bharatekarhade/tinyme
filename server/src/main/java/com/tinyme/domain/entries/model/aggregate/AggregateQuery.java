package com.tinyme.domain.entries.model.aggregate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record AggregateQuery(
        String kind,
        AggregateMetric metric,
        String field,
        Map<String, Object> where,
        LocalDate from,
        LocalDate to,
        GroupBy groupBy
) {
    private static final Pattern FIELD_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    public static final int MAX_BUCKETS = 366;

    public AggregateQuery {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(metric, "metric");
        groupBy = groupBy == null ? GroupBy.NONE : groupBy;
        where = where == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(where));

        if (kind.isBlank()) throw new IllegalArgumentException("kind is required");
        if (metric.requiresField() && (field == null || field.isBlank())) {
            throw new IllegalArgumentException("field is required for metric " + metric.name().toLowerCase());
        }
        if (field != null && !FIELD_PATTERN.matcher(field).matches()) {
            throw new IllegalArgumentException("field must start with a lowercase letter and contain only lowercase letters, digits or underscores");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("to must be on or after from");
        }
        if (groupBy.grouped() && from != null && to != null && bucketCount(from, to, groupBy) > MAX_BUCKETS) {
            throw new IllegalArgumentException("date range may produce at most " + MAX_BUCKETS + " buckets");
        }
    }

    public AggregateQuery withKind(String resolvedKind) {
        return new AggregateQuery(resolvedKind, metric, field, where, from, to, groupBy);
    }

    private static long bucketCount(LocalDate from, LocalDate to, GroupBy groupBy) {
        return switch (groupBy) {
            case NONE -> 0;
            case DAY -> ChronoUnit.DAYS.between(from, to) + 1;
            case WEEK -> ChronoUnit.WEEKS.between(from.with(DayOfWeek.MONDAY),
                    to.with(DayOfWeek.MONDAY)) + 1;
            case MONTH -> ChronoUnit.MONTHS.between(from.withDayOfMonth(1), to.withDayOfMonth(1)) + 1;
        };
    }
}
