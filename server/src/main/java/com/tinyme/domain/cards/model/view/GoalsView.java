package com.tinyme.domain.cards.model.view;

import java.util.List;

public record GoalsView(String label, List<GoalItemView> items) implements BlockView {
    public GoalsView { items = List.copyOf(items); }
}
