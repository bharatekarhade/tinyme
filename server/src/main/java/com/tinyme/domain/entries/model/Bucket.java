package com.tinyme.domain.entries.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Bucket(LocalDate period, BigDecimal value, long entries) {
}
