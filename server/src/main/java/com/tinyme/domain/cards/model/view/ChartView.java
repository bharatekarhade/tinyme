package com.tinyme.domain.cards.model.view;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

public record ChartView(String label, String unit, String style, List<Point> points,
                        @JsonProperty("goal_line") BigDecimal goalLine,
                        @JsonProperty("peak_index") Integer peakIndex) implements BlockView {
    public ChartView { points = List.copyOf(points); }
}
