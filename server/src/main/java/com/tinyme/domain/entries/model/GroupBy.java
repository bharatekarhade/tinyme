package com.tinyme.domain.entries.model;

public enum GroupBy {
    NONE(null, null), DAY("day", "1 day"), WEEK("week", "1 week"), MONTH("month", "1 month");

    private final String unit;
    private final String stepInterval;

    GroupBy(String unit, String stepInterval) {
        this.unit = unit;
        this.stepInterval = stepInterval;
    }

    public boolean grouped() {
        return this != NONE;
    }

    public String unit() {
        if (!grouped()) throw new IllegalStateException("NONE has no date_trunc unit");
        return unit;
    }

    public String stepInterval() {
        if (!grouped()) throw new IllegalStateException("NONE has no step interval");
        return stepInterval;
    }
}
