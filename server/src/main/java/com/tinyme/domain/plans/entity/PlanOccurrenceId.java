package com.tinyme.domain.plans.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class PlanOccurrenceId implements Serializable {
    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    protected PlanOccurrenceId() { }

    public PlanOccurrenceId(UUID planId, LocalDate day) {
        this.planId = Objects.requireNonNull(planId, "planId");
        this.day = Objects.requireNonNull(day, "day");
    }

    public UUID getPlanId() { return planId; }
    public LocalDate getDay() { return day; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PlanOccurrenceId that)) return false;
        return Objects.equals(planId, that.planId) && Objects.equals(day, that.day);
    }

    @Override
    public int hashCode() {
        return Objects.hash(planId, day);
    }
}
