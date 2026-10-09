package com.tinyme.domain.entries.model.aggregate;

import java.math.BigDecimal;

public record TodayTotal(String kind, String type, BigDecimal quantity) {
}
