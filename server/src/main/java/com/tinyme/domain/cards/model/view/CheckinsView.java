package com.tinyme.domain.cards.model.view;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record CheckinsView(String label, List<Slot> slots,
                           @JsonProperty("done_count") int doneCount, Integer goal,
                           StreakView streak,
                           @JsonProperty("average_day") AverageDayView averageDay) implements BlockView {
    public CheckinsView { slots = List.copyOf(slots); }
}
