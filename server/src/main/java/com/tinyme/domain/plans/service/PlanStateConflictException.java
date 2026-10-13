package com.tinyme.domain.plans.service;

public final class PlanStateConflictException extends RuntimeException {
    public PlanStateConflictException(String message) {
        super(message);
    }
}
