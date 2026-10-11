package com.tinyme.domain.cards.model.view;

import java.math.BigDecimal;

public record ProgressView(String label, String unit, String style, BigDecimal value,
                           BigDecimal goal, BigDecimal remaining, BigDecimal pct, long entries) implements BlockView {
}
