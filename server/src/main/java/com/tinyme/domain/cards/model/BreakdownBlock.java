package com.tinyme.domain.cards.model;

import com.tinyme.domain.entries.model.aggregate.Period;
import java.util.Objects;

public record BreakdownBlock(String label, MetricSpec metric, Period period, String by, int top,
                             BreakdownStyle style, String unit) implements MetricBlock {
    public BreakdownBlock {
        Objects.requireNonNull(label, "breakdown.label is required");
        Objects.requireNonNull(metric, "breakdown.metric is required");
        Objects.requireNonNull(period, "breakdown.period is required");
        Objects.requireNonNull(by, "breakdown.by is required");
        Objects.requireNonNull(style, "breakdown.style is required");
        if (!by.matches("^[a-z][a-z0-9_]{0,31}$")) throw new IllegalArgumentException("by must match ^[a-z][a-z0-9_]{0,31}$");
        if (metric.op() != com.tinyme.domain.entries.model.aggregate.AggregateMetric.COUNT
                && metric.op() != com.tinyme.domain.entries.model.aggregate.AggregateMetric.SUM) {
            throw new IllegalArgumentException("breakdown metric must be count or sum");
        }
        if (top < 1 || top > 5) throw new IllegalArgumentException("top must be between 1 and 5");
    }
}
