package com.tinyme.domain.cards.model.view;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Compares the selected period's daily value with the average before it:
 * calendar days for ordinary totals, logged nights for sleep hours.
 * With no entries before the period, only {@code currentPerDay} is set.
 */
public record AverageDayView(BigDecimal baseline,
                             @JsonProperty("current_per_day") BigDecimal currentPerDay,
                             @JsonProperty("delta_pct") BigDecimal deltaPct,
                             String basis) {

    public AverageDayView(BigDecimal baseline, BigDecimal currentPerDay, BigDecimal deltaPct) {
        this(baseline, currentPerDay, deltaPct, "day");
    }
}
