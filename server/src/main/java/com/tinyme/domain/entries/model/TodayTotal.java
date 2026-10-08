package com.tinyme.domain.entries.model;

import java.math.BigDecimal;

public record TodayTotal(String kind, String type, BigDecimal quantity) {
}
