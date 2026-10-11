package com.tinyme.domain.cards.model.view;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record GoalItemView(String label, BigDecimal value, BigDecimal goal, BigDecimal pct, String unit,
                           @JsonProperty("average_day") AverageDayView averageDay) {
}
