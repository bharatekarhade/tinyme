package com.tinyme.domain.entries.model.aggregate;

import java.math.BigDecimal;
import java.util.List;

public record BreakdownResult(List<BreakdownSlice> slices, BreakdownOther other, BigDecimal total) {
    public BreakdownResult { slices = List.copyOf(slices); }
}
