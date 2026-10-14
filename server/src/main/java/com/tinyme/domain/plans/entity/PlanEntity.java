package com.tinyme.domain.plans.entity;

import com.tinyme.domain.people.entity.PersonEntity;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.PlanSource;
import com.tinyme.domain.plans.model.PlanStatus;
import com.tinyme.domain.plans.model.Repeat;
import com.tinyme.domain.plans.model.RepeatFrequency;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "plans")
public class PlanEntity {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Convert(converter = PlanKindConverter.class)
    @Column(name = "kind", nullable = false)
    private PlanKind kind;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "notes")
    private String notes;

    @Column(name = "place")
    private String place;

    @Column(name = "zone", nullable = false)
    private String zone;

    @Column(name = "start_day", nullable = false)
    private LocalDate startDay;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "end_day")
    private LocalDate endDay;

    @Convert(converter = RepeatFrequencyConverter.class)
    @Column(name = "repeat_freq")
    private RepeatFrequency repeatFreq;

    @Column(name = "repeat_interval", nullable = false)
    private int repeatInterval = 1;

    @Column(name = "repeat_days")
    private Short repeatDays;

    @Column(name = "repeat_until")
    private LocalDate repeatUntil;

    @Column(name = "habit_kind")
    private String habitKind;

    @Column(name = "remind_minutes")
    private Integer remindMinutes;

    @Convert(converter = PlanStatusConverter.class)
    @Column(name = "status", nullable = false)
    private PlanStatus status = PlanStatus.ACTIVE;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Convert(converter = PlanSourceConverter.class)
    @Column(name = "source", nullable = false)
    private PlanSource source = PlanSource.CHAT;

    @Column(name = "external_id")
    private String externalId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "plan_people",
            joinColumns = @JoinColumn(name = "plan_id"),
            inverseJoinColumns = @JoinColumn(name = "person_id"))
    private Set<PersonEntity> people = new LinkedHashSet<>();

    protected PlanEntity() { }

    public PlanEntity(PlanKind kind, String title, String notes, String place, ZoneId zone,
                      LocalDate startDay, LocalTime startTime, LocalTime endTime, LocalDate endDay,
                      Repeat repeat, String habitKind, Integer remindMinutes, PlanSource source,
                      String externalId, Set<PersonEntity> people) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.title = title == null ? null : title.strip();
        this.notes = notes;
        this.place = place;
        this.zone = Objects.requireNonNull(zone, "zone").getId();
        this.startDay = Objects.requireNonNull(startDay, "startDay");
        this.startTime = startTime;
        this.endTime = endTime;
        this.endDay = endDay;
        this.habitKind = normalizeHabitKind(habitKind);
        this.remindMinutes = remindMinutes;
        this.source = Objects.requireNonNull(source, "source");
        this.externalId = externalId;
        applyRepeat(repeat);
        validate();
        replacePeople(people == null ? Set.of() : people);
    }

    public UUID getId() { return id; }
    public PlanKind getKind() { return kind; }
    public String getTitle() { return title; }
    public String getNotes() { return notes; }
    public String getPlace() { return place; }
    public String getZone() { return zone; }
    public LocalDate getStartDay() { return startDay; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
    public LocalDate getEndDay() { return endDay; }
    public String getHabitKind() { return habitKind; }
    public Integer getRemindMinutes() { return remindMinutes; }
    public PlanStatus getStatus() { return status; }
    public Instant getCompletedAt() { return completedAt; }
    public PlanSource getSource() { return source; }
    public String getExternalId() { return externalId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public Set<PersonEntity> getPeople() { return Collections.unmodifiableSet(new LinkedHashSet<>(people)); }

    public Repeat getRepeat() {
        if (repeatFreq == null) return null;
        return new Repeat(repeatFreq, repeatInterval, decodeDays(repeatDays), repeatUntil);
    }

    public void markDone(Instant now) {
        ensureLive();
        if (status == PlanStatus.CANCELLED) throw new IllegalStateException("Cancelled plans cannot be marked done");
        if (kind != PlanKind.TASK) throw new IllegalStateException("Only tasks can be marked done");
        if (repeatFreq != null) throw new IllegalStateException("Repeating plans are completed per occurrence");
        status = PlanStatus.DONE;
        completedAt = Objects.requireNonNull(now, "now");
    }

    public void cancel() {
        ensureLive();
        status = PlanStatus.CANCELLED;
        completedAt = null;
    }

    public void undoDone() {
        ensureLive();
        if (status == PlanStatus.DONE) {
            status = PlanStatus.ACTIVE;
            completedAt = null;
        }
    }

    public void edit(PlanKind kind, String title, String notes, String place, String habitKind,
                     Integer remindMinutes, Repeat repeat, LocalDate startDay, LocalTime startTime,
                     LocalTime endTime, LocalDate endDay) {
        ensureLive();
        Objects.requireNonNull(kind, "kind");
        String candidateTitle = title == null ? null : title.strip();
        String candidateHabitKind = normalizeHabitKind(habitKind);
        Objects.requireNonNull(startDay, "startDay");
        PlanStatus candidateStatus = status;
        Instant candidateCompletedAt = completedAt;
        boolean scheduleChanged = !Objects.equals(this.startDay, startDay)
                || !Objects.equals(this.startTime, startTime)
                || !Objects.equals(getRepeat(), repeat);
        // Only a task can be done, so a done task that becomes an event is open again.
        if (candidateStatus == PlanStatus.DONE && (scheduleChanged || kind != PlanKind.TASK)) {
            candidateStatus = PlanStatus.ACTIVE;
            candidateCompletedAt = null;
        }

        validateValues(kind, candidateTitle, zone, startDay, startTime, endTime, endDay,
                repeat, remindMinutes, candidateStatus, source);

        this.kind = kind;
        this.title = candidateTitle;
        this.notes = notes;
        this.place = place;
        this.habitKind = candidateHabitKind;
        this.remindMinutes = remindMinutes;
        this.startDay = startDay;
        this.startTime = startTime;
        this.endTime = endTime;
        this.endDay = endDay;
        applyRepeat(repeat);
        this.status = candidateStatus;
        this.completedAt = candidateCompletedAt;
    }

    public void softDelete(Instant now) {
        if (deletedAt == null) deletedAt = Objects.requireNonNull(now, "now");
    }

    public void restore() {
        if (deletedAt != null) deletedAt = null;
    }

    public void replacePeople(Set<PersonEntity> people) {
        ensureLive();
        this.people = new LinkedHashSet<>(Objects.requireNonNull(people, "people"));
    }

    private void applyRepeat(Repeat repeat) {
        if (repeat == null) {
            repeatFreq = null;
            repeatInterval = 1;
            repeatDays = null;
            repeatUntil = null;
            return;
        }
        repeatFreq = repeat.freq();
        repeatInterval = repeat.interval();
        repeatDays = encodeDays(repeat.days());
        repeatUntil = repeat.until();
    }

    private void ensureLive() {
        if (deletedAt != null) throw new IllegalStateException("Deleted plans cannot be changed");
    }

    private void validate() {
        validateValues(kind, title, zone, startDay, startTime, endTime, endDay,
                getRepeat(), remindMinutes, status, source);
    }

    private static void validateValues(PlanKind kind, String title, String zone, LocalDate startDay,
                                       LocalTime startTime, LocalTime endTime, LocalDate endDay,
                                       Repeat repeat, Integer remindMinutes, PlanStatus status,
                                       PlanSource source) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(startDay, "startDay");
        Objects.requireNonNull(source, "source");
        if (title == null || title.isBlank() || title.length() > 120) {
            throw new IllegalArgumentException("title must contain 1 to 120 non-blank characters");
        }
        if (remindMinutes != null && (remindMinutes < 0 || remindMinutes > 10080)) {
            throw new IllegalArgumentException("remindMinutes must be between 0 and 10080");
        }
        if (endDay != null && endDay.isBefore(startDay)) {
            throw new IllegalArgumentException("endDay must not be before startDay");
        }
        if (endTime != null && startTime == null) {
            throw new IllegalArgumentException("endTime requires startTime");
        }
        if (endTime != null && !endTime.isAfter(startTime)
                && (endDay == null || !endDay.isAfter(startDay))) {
            throw new IllegalArgumentException("an overnight event must end on a later day");
        }
        if (kind == PlanKind.TASK && (endTime != null || endDay != null)) {
            throw new IllegalArgumentException("tasks cannot have endTime or endDay");
        }
        if (repeat != null && repeat.until() != null && repeat.until().isBefore(startDay)) {
            throw new IllegalArgumentException("repeatUntil must not be before startDay");
        }
        if (repeat != null && endDay != null) {
            throw new IllegalArgumentException("repeating plans cannot span multiple days");
        }
        if (status == PlanStatus.DONE && repeat != null) {
            throw new IllegalArgumentException("repeating plans are completed per occurrence");
        }
    }

    private static String normalizeHabitKind(String habitKind) {
        if (habitKind == null || habitKind.isBlank()) return null;
        return habitKind.strip().toLowerCase(Locale.ROOT);
    }

    private static Short encodeDays(Set<DayOfWeek> days) {
        if (days.isEmpty()) return null;
        int mask = 0;
        for (DayOfWeek day : days) mask |= 1 << (day.getValue() - 1);
        return (short) mask;
    }

    private static Set<DayOfWeek> decodeDays(Short mask) {
        if (mask == null || mask == 0) return Set.of();
        Set<DayOfWeek> days = new LinkedHashSet<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            if ((mask & (1 << (day.getValue() - 1))) != 0) days.add(day);
        }
        if ((mask & ~0x7f) != 0) throw new IllegalStateException("Stored repeat_days has unknown bits");
        return Set.copyOf(days);
    }
}
