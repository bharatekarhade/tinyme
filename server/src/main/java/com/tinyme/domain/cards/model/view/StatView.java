package com.tinyme.domain.cards.model.view;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record StatView(String label, String unit, BigDecimal value, BigDecimal goal, long entries,
                       BigDecimal previous,
                       @JsonProperty("delta_pct") BigDecimal deltaPct,
                       @JsonProperty("average_day") AverageDayView averageDay) implements BlockView {
}
