package com.tinyme.domain.health.model;

/**
 * What a sync changed. {@code upserted} counts new and changed entries; {@code merged} counts chat entries
 * folded into a Health one (none yet: the workout merge comes later).
 */
public record HealthSyncResult(int upserted, int unchanged, int deleted, int merged) { }
