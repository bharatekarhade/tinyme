package com.tinyme.domain.cards.model;

import com.tinyme.domain.entries.model.aggregate.Period;
import java.util.List;
import java.util.Objects;

public record GoalsBlock(String label, Period period, List<GoalItem> items) implements Block {
    public GoalsBlock {
        Objects.requireNonNull(label, "goals.label is required");
        Objects.requireNonNull(period, "goals.period is required");
        items = List.copyOf(items);
        if (items.isEmpty() || items.size() > 4) throw new IllegalArgumentException("goals.items must contain 1–4 items");
    }
}
