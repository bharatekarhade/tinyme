package com.tinyme.domain.cards.model.view;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

public record BreakdownView(String label, String style, String unit, BigDecimal total,
                            List<Slice> slices, OtherSlice other,
                            @JsonProperty("average_day") AverageDayView averageDay) implements BlockView {
    public BreakdownView { slices = List.copyOf(slices); }
}
