package com.tinyme.domain.entries.model.aggregate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record AggregateQuery(
        List<String> kinds,
        AggregateMetric metric,
        String field,
        Map<String, Object> where,
        LocalDate from,
        LocalDate to,
        GroupBy groupBy
) {
    private static final Pattern KIND_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    private static final Pattern FIELD_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    /** Engine ceiling; the agent-facing tool applies its narrower 366-bucket limit. */
    public static final int MAX_BUCKETS = 400;
    public static final int MAX_TOOL_BUCKETS = 366;

    public AggregateQuery {
        kinds = List.copyOf(validateKinds(kinds));
        Objects.requireNonNull(metric, "metric");
        groupBy = groupBy == null ? GroupBy.NONE : groupBy;
        where = where == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(where));

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

    public boolean allKinds() {
        return kinds.equals(List.of("*"));
    }

    public AggregateQuery withKinds(List<String> resolvedKinds) {
        return new AggregateQuery(resolvedKinds, metric, field, where, from, to, groupBy);
    }

    public static List<String> validateKinds(List<String> kinds) {
        Objects.requireNonNull(kinds, "kinds");
        if (kinds.isEmpty()) throw new IllegalArgumentException("kinds must contain 1–5 values");
        if (kinds.size() > 5) throw new IllegalArgumentException("kinds must contain at most 5 values");
        if (kinds.contains("*") && !kinds.equals(List.of("*"))) {
            throw new IllegalArgumentException("'*' must be the only kind");
        }
        if (kinds.stream().anyMatch(kind -> kind == null || (!"*".equals(kind)
                && !KIND_PATTERN.matcher(kind).matches()))) {
            throw new IllegalArgumentException("each kind must contain 2–32 lowercase letters, digits or underscores, starting with a letter");
        }
        return kinds;
    }

    public static long bucketCount(LocalDate from, LocalDate to, GroupBy groupBy) {
        return switch (groupBy) {
            case NONE -> 0;
            case DAY -> ChronoUnit.DAYS.between(from, to) + 1;
            case WEEK -> ChronoUnit.WEEKS.between(from.with(DayOfWeek.MONDAY), to.with(DayOfWeek.MONDAY)) + 1;
            case MONTH -> ChronoUnit.MONTHS.between(from.withDayOfMonth(1), to.withDayOfMonth(1)) + 1;
        };
    }
}
