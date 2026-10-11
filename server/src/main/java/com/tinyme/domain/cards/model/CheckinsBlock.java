package com.tinyme.domain.cards.model;

import com.tinyme.domain.entries.model.aggregate.Period;
import java.util.Objects;

public record CheckinsBlock(String label, MetricSpec metric, Period period, Integer goal,
                            GoalPeriod goalPeriod) implements MetricBlock {
    public CheckinsBlock {
        Objects.requireNonNull(label, "checkins.label is required");
        Objects.requireNonNull(metric, "checkins.metric is required");
        Objects.requireNonNull(period, "checkins.period is required");
        if (goal != null && goal <= 0) throw new IllegalArgumentException("goal must be greater than 0");
        if (goalPeriod != null && goal == null) throw new IllegalArgumentException("goal_period requires goal");
    }
}
