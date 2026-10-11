package com.tinyme.domain.cards.model;

import com.tinyme.domain.entries.model.aggregate.Period;
import java.math.BigDecimal;
import java.util.Objects;

public record StatBlock(String label, MetricSpec metric, Period period, String unit, BigDecimal goal,
                        GoalPeriod goalPeriod, ComparePeriod compare, Better better) implements MetricBlock {
    public StatBlock {
        Objects.requireNonNull(label, "stat.label is required");
        Objects.requireNonNull(metric, "stat.metric is required");
        Objects.requireNonNull(period, "stat.period is required");
        if (goal != null && goal.signum() <= 0) throw new IllegalArgumentException("goal must be greater than 0");
        if (goalPeriod != null && goal == null) throw new IllegalArgumentException("goal_period requires goal");
        if (better != null && compare == null) throw new IllegalArgumentException("better requires compare");
    }
}
