package com.tinyme.domain.cards.model.view;

import java.math.BigDecimal;

public record Slice(String key, BigDecimal value, BigDecimal pct) {
}
