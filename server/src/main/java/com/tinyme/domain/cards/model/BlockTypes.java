package com.tinyme.domain.cards.model;

/** Canonical wire type for every sealed card block. */
public final class BlockTypes {
    private BlockTypes() { }

    public static String name(Block block) {
        return switch (block) {
            case StatBlock ignored -> "stat";
            case ProgressBlock ignored -> "progress";
            case GoalsBlock ignored -> "goals";
            case ChartBlock ignored -> "chart";
            case CheckinsBlock ignored -> "checkins";
            case BreakdownBlock ignored -> "breakdown";
            case LatestBlock ignored -> "latest";
        };
    }
}
