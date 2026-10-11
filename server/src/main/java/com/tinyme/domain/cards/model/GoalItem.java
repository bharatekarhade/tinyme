package com.tinyme.domain.cards.model;

import java.math.BigDecimal;
import java.util.Objects;

public record GoalItem(String label, MetricSpec metric, BigDecimal goal, String unit) {
    public GoalItem {
        Objects.requireNonNull(label, "goal item label is required");
        Objects.requireNonNull(metric, "goal item metric is required");
        if (goal == null || goal.signum() <= 0) throw new IllegalArgumentException("goal must be greater than 0");
    }
}
