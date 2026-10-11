package com.tinyme.domain.cards.model.view;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StatView.class, name = "stat"),
        @JsonSubTypes.Type(value = ProgressView.class, name = "progress"),
        @JsonSubTypes.Type(value = GoalsView.class, name = "goals"),
        @JsonSubTypes.Type(value = ChartView.class, name = "chart"),
        @JsonSubTypes.Type(value = CheckinsView.class, name = "checkins"),
        @JsonSubTypes.Type(value = BreakdownView.class, name = "breakdown"),
        @JsonSubTypes.Type(value = LatestView.class, name = "latest"),
        @JsonSubTypes.Type(value = UnavailableView.class, name = "unavailable")
})
public sealed interface BlockView permits StatView, ProgressView, GoalsView, ChartView,
        CheckinsView, BreakdownView, LatestView, UnavailableView {
}
