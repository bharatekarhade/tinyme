package com.tinyme.domain.plans.model;

/** Persisted per-day override for a repeating plan. Computed occurrence states are defined separately in U2. */
public enum OccurrenceExceptionStatus {
    DONE,
    SKIPPED,
    MOVED
}
