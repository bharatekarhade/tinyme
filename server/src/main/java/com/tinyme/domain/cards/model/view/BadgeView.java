package com.tinyme.domain.cards.model.view;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record BadgeView(String kind, String period,
                        @JsonProperty("delta_pct") BigDecimal deltaPct,
                        String better,
                        @JsonProperty("compare_label") String compareLabel,
                        Integer days) {
}
