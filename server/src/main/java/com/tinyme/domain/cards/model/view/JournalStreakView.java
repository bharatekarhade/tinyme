package com.tinyme.domain.cards.model.view;

import java.util.List;

/**
 * The journaling streak as of today: days in a row with at least one entry of any kind.
 * {@code days} is the last seven days, oldest first, ending today. A streak still counts
 * while today is empty, so {@code current} can be positive when the last day is false.
 */
public record JournalStreakView(int current, int best, List<Boolean> days) {
    public JournalStreakView { days = List.copyOf(days); }
}
