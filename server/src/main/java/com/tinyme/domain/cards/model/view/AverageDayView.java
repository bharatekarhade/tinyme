package com.tinyme.domain.cards.model.view;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Compares the selected period's per-day value with the daily average before it:
 * the preceding 30 days, or fewer when tracking began inside that window.
 * With no entries before the period, only {@code currentPerDay} is set.
 */
public record AverageDayView(BigDecimal baseline,
                             @JsonProperty("current_per_day") BigDecimal currentPerDay,
                             @JsonProperty("delta_pct") BigDecimal deltaPct) {
}
