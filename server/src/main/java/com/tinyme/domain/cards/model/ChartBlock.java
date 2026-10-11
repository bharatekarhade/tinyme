package com.tinyme.domain.cards.model;

import com.tinyme.domain.entries.model.aggregate.Period;
import java.math.BigDecimal;
import java.util.Objects;

public record ChartBlock(String label, MetricSpec metric, Period period, ChartBucket bucket,
                         ChartStyle style, BigDecimal goalLine, Highlight highlight, String unit) implements MetricBlock {
    public ChartBlock {
        Objects.requireNonNull(label, "chart.label is required");
        Objects.requireNonNull(metric, "chart.metric is required");
        Objects.requireNonNull(period, "chart.period is required");
        Objects.requireNonNull(bucket, "chart.bucket is required");
        Objects.requireNonNull(style, "chart.style is required");
        Objects.requireNonNull(highlight, "chart.highlight is required");
        if (goalLine != null && goalLine.signum() <= 0) throw new IllegalArgumentException("goal_line must be greater than 0");
    }
}
