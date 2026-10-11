package com.tinyme.domain.cards.model.view;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** Compares the selected period's per-day value with the preceding 30-day daily average. */
public record AverageDayView(BigDecimal baseline,
                             @JsonProperty("current_per_day") BigDecimal currentPerDay,
                             @JsonProperty("delta_pct") BigDecimal deltaPct) {
}
