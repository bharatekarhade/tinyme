package com.tinyme.domain.cards.model.view;

import java.math.BigDecimal;

public record GoalItemView(String label, BigDecimal value, BigDecimal goal, BigDecimal pct, String unit) {
}
