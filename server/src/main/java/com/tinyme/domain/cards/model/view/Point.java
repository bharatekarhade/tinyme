package com.tinyme.domain.cards.model.view;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Point(LocalDate period, BigDecimal value, long entries, String state) {
}
