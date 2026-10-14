package com.tinyme.domain.plans.entity;

import com.tinyme.domain.plans.model.OccurrenceExceptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

@Entity
@Table(name = "plan_occurrences")
public class PlanOccurrenceEntity {
    @EmbeddedId
    private PlanOccurrenceId id;

    @MapsId("planId")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private PlanEntity plan;

    @Convert(converter = OccurrenceExceptionStatusConverter.class)
    @Column(name = "status", nullable = false)
    private OccurrenceExceptionStatus status;

    @Column(name = "moved_day")
    private LocalDate movedDay;

    @Column(name = "moved_time")
    private LocalTime movedTime;

    @Column(name = "moved_end_time")
    private LocalTime movedEndTime;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PlanOccurrenceEntity() { }

    private PlanOccurrenceEntity(PlanEntity plan, LocalDate day, OccurrenceExceptionStatus status,
                                 LocalDate movedDay, LocalTime movedTime, LocalTime movedEndTime, Instant completedAt) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.id = new PlanOccurrenceId(plan.getId(), day);
        this.status = Objects.requireNonNull(status, "status");
        if ((status == OccurrenceExceptionStatus.MOVED) != (movedDay != null)) {
            throw new IllegalArgumentException("movedDay must be set exactly when status is moved");
        }
        this.movedDay = movedDay;
        this.movedTime = movedTime;
        if (movedEndTime != null && (movedTime == null || !movedEndTime.isAfter(movedTime))) {
            throw new IllegalArgumentException("movedEndTime must be after movedTime");
        }
        this.movedEndTime = movedEndTime;
        this.completedAt = completedAt;
    }

    public static PlanOccurrenceEntity done(PlanEntity plan, LocalDate day, Instant completedAt) {
        return new PlanOccurrenceEntity(plan, day, OccurrenceExceptionStatus.DONE,
                null, null, null, Objects.requireNonNull(completedAt, "completedAt"));
    }

    public static PlanOccurrenceEntity doneMoved(PlanEntity plan, LocalDate day, LocalDate movedDay,
                                                 LocalTime movedTime, LocalTime movedEndTime, Instant completedAt) {
        return new PlanOccurrenceEntity(plan, day, OccurrenceExceptionStatus.MOVED,
                Objects.requireNonNull(movedDay, "movedDay"), movedTime, movedEndTime,
                Objects.requireNonNull(completedAt, "completedAt"));
    }

    public static PlanOccurrenceEntity skipped(PlanEntity plan, LocalDate day) {
        return new PlanOccurrenceEntity(plan, day, OccurrenceExceptionStatus.SKIPPED, null, null, null, null);
    }

    public static PlanOccurrenceEntity deleted(PlanEntity plan, LocalDate day) {
        return new PlanOccurrenceEntity(plan, day, OccurrenceExceptionStatus.DELETED, null, null, null, null);
    }

    public static PlanOccurrenceEntity moved(PlanEntity plan, LocalDate day,
                                             LocalDate newDay, LocalTime newTime) {
        return moved(plan, day, newDay, newTime, null);
    }

    public static PlanOccurrenceEntity moved(PlanEntity plan, LocalDate day,
                                             LocalDate newDay, LocalTime newTime, LocalTime newEndTime) {
        return new PlanOccurrenceEntity(plan, day, OccurrenceExceptionStatus.MOVED,
                Objects.requireNonNull(newDay, "newDay"), newTime, newEndTime, null);
    }

    public PlanOccurrenceId getId() { return id; }
    public PlanEntity getPlan() { return plan; }
    public LocalDate getDay() { return id.getDay(); }
    public OccurrenceExceptionStatus getStatus() { return status; }
    public LocalDate getMovedDay() { return movedDay; }
    public LocalTime getMovedTime() { return movedTime; }
    public LocalTime getMovedEndTime() { return movedEndTime; }
    public Instant getCompletedAt() { return completedAt; }
}
