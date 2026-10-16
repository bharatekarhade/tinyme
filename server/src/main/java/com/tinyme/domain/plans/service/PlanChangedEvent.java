package com.tinyme.domain.plans.service;

/** Signals that user-owned plan data changed and its Google mirror should be refreshed after commit. */
public record PlanChangedEvent() { }
