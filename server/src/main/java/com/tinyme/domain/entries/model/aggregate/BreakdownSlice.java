package com.tinyme.domain.entries.model.aggregate;

import java.math.BigDecimal;

public record BreakdownSlice(String key, BigDecimal value, BigDecimal pct) { }
