package com.tinyme.domain.cards.model;

import com.tinyme.domain.entries.model.aggregate.Period;
import java.math.BigDecimal;
import java.util.Objects;

public record ProgressBlock(String label, MetricSpec metric, Period period, BigDecimal goal, String unit,
                            GoalPeriod goalPeriod, ProgressStyle style) implements MetricBlock {
    public ProgressBlock {
        Objects.requireNonNull(label, "progress.label is required");
        Objects.requireNonNull(metric, "progress.metric is required");
        Objects.requireNonNull(period, "progress.period is required");
        Objects.requireNonNull(style, "progress.style is required");
        if (goal == null || goal.signum() <= 0) throw new IllegalArgumentException("goal must be greater than 0");
    }
}
