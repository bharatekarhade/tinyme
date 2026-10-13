package com.tinyme.domain.plans.service;

import com.tinyme.domain.people.model.Birthday;
import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.plans.model.OccurrenceExceptionStatus;
import com.tinyme.domain.plans.model.OccurrenceStatus;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.PlanOccurrence;
import com.tinyme.domain.plans.model.PlanSource;
import com.tinyme.domain.plans.model.PlanStatus;
import com.tinyme.domain.plans.model.Repeat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure expansion and status calculation for plan rules. Repository loading stays outside this class. */
public final class PlanCalendar {
    public static final int MAX_DAYS = 62;

    public List<PlanOccurrence> expand(LocalDate from, LocalDate to, Instant now, ZoneId userZone,
                                       Collection<PlanInput> plans, Collection<ExceptionInput> exceptions,
                                       Collection<BirthdayInput> birthdays, Collection<HabitDay> habitDays,
                                       boolean includeCancelled) {
        validateRange(from, to);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(userZone, "userZone");
        Objects.requireNonNull(plans, "plans");
        Objects.requireNonNull(exceptions, "exceptions");
        Objects.requireNonNull(birthdays, "birthdays");
        Objects.requireNonNull(habitDays, "habitDays");

        LocalDate today = LocalDate.ofInstant(now, userZone);
        Map<UUID, Map<LocalDate, ExceptionInput>> exceptionsByPlan = indexExceptions(exceptions);
        Set<HabitDay> completedHabits = Set.copyOf(habitDays);
        List<PlanOccurrence> result = new ArrayList<>();
        Set<UUID> planIds = new HashSet<>();

        for (PlanInput plan : plans) {
            if (!planIds.add(plan.id())) throw new IllegalArgumentException("duplicate plan id " + plan.id());
            if (plan.status() == PlanStatus.CANCELLED && !includeCancelled) continue;
            Map<LocalDate, ExceptionInput> planExceptions = exceptionsByPlan.getOrDefault(plan.id(), Map.of());
            List<PlanOccurrence> forPlan = expandPlan(plan, planExceptions, from, to, now, today,
                    completedHabits);
            result.addAll(forPlan);
        }

        result.addAll(expandBirthdays(birthdays, from, to, today));
        result.sort(Comparator.comparing(PlanOccurrence::day)
                .thenComparing(PlanOccurrence::startTime, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(PlanOccurrence::title)
                .thenComparing(PlanOccurrence::id));
        return List.copyOf(result);
    }

    private static List<PlanOccurrence> expandPlan(PlanInput plan, Map<LocalDate, ExceptionInput> exceptions,
                                                    LocalDate from, LocalDate to, Instant now, LocalDate today,
                                                    Set<HabitDay> habitDays) {
        List<PlanOccurrence> occurrences = new ArrayList<>();

        if (plan.repeat() == null && overlaps(plan.startDay(), plan.endDay(), from, to)) {
            addOriginal(plan, plan.startDay(), exceptions.get(plan.startDay()), occurrences, now, today, habitDays);
        } else if (plan.repeat() != null) {
            LocalDate first = plan.startDay().isAfter(from) ? plan.startDay() : from;
            for (LocalDate day = first; !day.isAfter(to); day = day.plusDays(1)) {
                if (occursOn(plan.startDay(), plan.repeat(), day)) {
                    addOriginal(plan, day, exceptions.get(day), occurrences, now, today, habitDays);
                }
            }
        } else if (plan.kind() == PlanKind.TASK && plan.startDay().isBefore(from)
                && plan.status() == PlanStatus.ACTIVE) {
            // A one-off active task remains in the overdue section until completed or cancelled.
            addOriginal(plan, plan.startDay(), exceptions.get(plan.startDay()), occurrences, now, today, habitDays);
        }

        for (ExceptionInput exception : exceptions.values()) {
            if (exception.status() != OccurrenceExceptionStatus.MOVED
                    || !within(exception.movedDay(), from, to)
                    || !occursOn(plan.startDay(), plan.repeat(), exception.originalDay())) continue;
            occurrences.add(create(plan, exception.originalDay(), exception.movedDay(), exception.movedTime(),
                    exception, now, today, habitDays));
        }

        return occurrences;
    }

    private static void addOriginal(PlanInput plan, LocalDate originalDay, ExceptionInput exception,
                                    List<PlanOccurrence> out, Instant now, LocalDate today,
                                    Set<HabitDay> habitDays) {
        if (exception != null && exception.status() == OccurrenceExceptionStatus.MOVED) {
            return;
        }
        LocalDate actualDay = originalDay;
        out.add(create(plan, originalDay, actualDay, null, exception, now, today, habitDays));
    }

    private static PlanOccurrence create(PlanInput plan, LocalDate originalDay, LocalDate day,
                                         LocalTime movedTime, ExceptionInput exception, Instant now,
                                         LocalDate today, Set<HabitDay> habitDays) {
        LocalTime startTime = movedTime == null ? plan.startTime() : movedTime;
        LocalDate endDay = plan.repeat() == null && day.equals(plan.startDay()) ? plan.endDay() : null;
        LocalTime endTime = plan.endTime();
        if (movedTime != null && plan.endTime() != null && plan.startTime() != null) {
            long durationMinutes = ChronoUnit.MINUTES.between(plan.startTime(), plan.endTime());
            if (durationMinutes < 0) durationMinutes += 24 * 60L;
            LocalDateTime movedEnd = LocalDateTime.of(day, movedTime).plusMinutes(durationMinutes);
            endDay = movedEnd.toLocalDate().equals(day) ? null : movedEnd.toLocalDate();
            endTime = movedEnd.toLocalTime();
        }

        boolean allDay = startTime == null;
        Instant startsAt = null;
        Instant endsAt = null;
        if (!allDay) {
            ZoneId zone = ZoneId.of(plan.zone());
            startsAt = LocalDateTime.of(day, startTime).atZone(zone).toInstant();
            LocalDate actualEndDay = endDay == null ? day : endDay;
            if (endTime == null) {
                endsAt = LocalDateTime.of(day, startTime).plusMinutes(60).atZone(zone).toInstant();
            } else {
                endsAt = LocalDateTime.of(actualEndDay, endTime).atZone(zone).toInstant();
            }
        }

        OccurrenceStatus status = status(plan, day, startsAt, endsAt, exception,
                now, today, habitDays);
        String id = plan.id() + ":" + originalDay;
        return new PlanOccurrence(id, plan.id(), originalDay, day, plan.startDay(), startTime, endTime, endDay,
                startsAt, endsAt, allDay, plan.kind(), plan.title(), plan.place(), plan.people(),
                status, plan.habitKind(), plan.repeat(), plan.source().name().toLowerCase(Locale.ROOT));
    }

    private static OccurrenceStatus status(PlanInput plan, LocalDate day,
                                           Instant startsAt, Instant endsAt, ExceptionInput exception,
                                           Instant now, LocalDate today, Set<HabitDay> habitDays) {
        if (plan.status() == PlanStatus.CANCELLED) return OccurrenceStatus.CANCELLED;
        if (exception != null) {
            if (exception.status() == OccurrenceExceptionStatus.SKIPPED) return OccurrenceStatus.SKIPPED;
            if (exception.status() == OccurrenceExceptionStatus.DONE || exception.completedAt() != null) {
                return OccurrenceStatus.DONE;
            }
        }
        if (plan.status() == PlanStatus.DONE) return OccurrenceStatus.DONE;
        if (plan.habitKind() != null && habitDays.contains(new HabitDay(plan.habitKind(), day))) {
            return OccurrenceStatus.DONE;
        }
        if (plan.kind() != PlanKind.EVENT) {
            return day.isBefore(today) ? OccurrenceStatus.MISSED : OccurrenceStatus.UPCOMING;
        }
        if (startsAt != null && endsAt != null) {
            if (!now.isBefore(startsAt) && now.isBefore(endsAt)) return OccurrenceStatus.IN_PROGRESS;
            if (!now.isBefore(endsAt)) return OccurrenceStatus.MISSED;
            return OccurrenceStatus.UPCOMING;
        }
        return day.isBefore(today) ? OccurrenceStatus.MISSED : OccurrenceStatus.UPCOMING;
    }

    private static List<PlanOccurrence> expandBirthdays(Collection<BirthdayInput> people, LocalDate from,
                                                        LocalDate to, LocalDate today) {
        List<PlanOccurrence> birthdays = new ArrayList<>();
        for (BirthdayInput person : people) {
            if (person.birthday() == null) continue;
            for (int year = from.getYear(); year <= to.getYear(); year++) {
                LocalDate day = person.birthday().monthDay().atYear(year);
                if (!within(day, from, to)) continue;
                String title = person.displayName() + "'s birthday";
                Integer birthYear = person.birthday().year();
                if (birthYear != null && year < birthYear) continue;
                if (birthYear != null) title += " (turns " + (year - birthYear) + ")";
                OccurrenceStatus status = day.isBefore(today) ? OccurrenceStatus.PAST : OccurrenceStatus.UPCOMING;
                String id = "birthday:" + person.slug() + ":" + day;
                PersonSnapshot snapshot = new PersonSnapshot(person.slug(), person.displayName(), person.aliases(),
                        person.relationship(), person.memoryPath());
                birthdays.add(new PlanOccurrence(id, null, day, day, null, null, null, null,
                        null, null, true, PlanKind.EVENT, title, null, List.of(snapshot), status,
                        null, null, "people"));
            }
        }
        return birthdays;
    }

    private static boolean occursOn(LocalDate startDay, Repeat repeat, LocalDate day) {
        if (day.isBefore(startDay)) return false;
        if (repeat == null) return day.equals(startDay);
        if (repeat.until() != null && day.isAfter(repeat.until())) return false;
        return switch (repeat.freq()) {
            case DAILY -> ChronoUnit.DAYS.between(startDay, day) % repeat.interval() == 0;
            case WEEKLY -> weeklyOccurrence(startDay, repeat, day);
            case MONTHLY -> monthlyOccurrence(startDay, repeat, day);
            case YEARLY -> yearlyOccurrence(startDay, repeat, day);
        };
    }

    private static boolean weeklyOccurrence(LocalDate startDay, Repeat repeat, LocalDate day) {
        LocalDate startMonday = startDay.with(DayOfWeek.MONDAY);
        LocalDate dayMonday = day.with(DayOfWeek.MONDAY);
        long weeks = ChronoUnit.WEEKS.between(startMonday, dayMonday);
        if (weeks % repeat.interval() != 0) return false;
        Set<DayOfWeek> days = repeat.days().isEmpty() ? Set.of(startDay.getDayOfWeek()) : repeat.days();
        return days.contains(day.getDayOfWeek());
    }

    private static boolean monthlyOccurrence(LocalDate startDay, Repeat repeat, LocalDate day) {
        long months = ChronoUnit.MONTHS.between(startDay.withDayOfMonth(1), day.withDayOfMonth(1));
        if (months < 0 || months % repeat.interval() != 0) return false;
        return day.getDayOfMonth() == Math.min(startDay.getDayOfMonth(), day.lengthOfMonth());
    }

    private static boolean yearlyOccurrence(LocalDate startDay, Repeat repeat, LocalDate day) {
        long years = (long) day.getYear() - startDay.getYear();
        if (years < 0 || years % repeat.interval() != 0 || day.getMonth() != startDay.getMonth()) return false;
        return day.getDayOfMonth() == Math.min(startDay.getDayOfMonth(), day.lengthOfMonth());
    }

    private static boolean overlaps(LocalDate start, LocalDate end, LocalDate from, LocalDate to) {
        LocalDate last = end == null ? start : end;
        return !start.isAfter(to) && !last.isBefore(from);
    }

    private static boolean within(LocalDate day, LocalDate from, LocalDate to) {
        return !day.isBefore(from) && !day.isAfter(to);
    }

    private static Map<UUID, Map<LocalDate, ExceptionInput>> indexExceptions(Collection<ExceptionInput> exceptions) {
        Map<UUID, Map<LocalDate, ExceptionInput>> result = new HashMap<>();
        for (ExceptionInput exception : exceptions) {
            ExceptionInput previous = result.computeIfAbsent(exception.planId(), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(exception.originalDay(), exception);
            if (previous != null) throw new IllegalArgumentException("duplicate exception for " + exception.planId()
                    + " on " + exception.originalDay());
        }
        return result;
    }

    public static void validateRange(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) throw new IllegalArgumentException("to must not be before from");
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException("Plan calendar range must not exceed " + MAX_DAYS + " days");
        }
    }

    /** habitKind is the canonical kind after following any entry_kinds merges. */
    public record PlanInput(UUID id, PlanKind kind, String title, String notes, String place, String zone,
                            LocalDate startDay, LocalTime startTime, LocalTime endTime, LocalDate endDay,
                            Repeat repeat, String habitKind, PlanStatus status, Instant completedAt,
                            PlanSource source, List<PersonSnapshot> people) {
        public PlanInput {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(title, "title");
            ZoneId.of(Objects.requireNonNull(zone, "zone"));
            Objects.requireNonNull(startDay, "startDay");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(source, "source");
            people = List.copyOf(Objects.requireNonNull(people, "people"));
        }
    }

    public record ExceptionInput(UUID planId, LocalDate originalDay, OccurrenceExceptionStatus status,
                                 LocalDate movedDay, LocalTime movedTime, Instant completedAt) {
        public ExceptionInput {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(originalDay, "originalDay");
            Objects.requireNonNull(status, "status");
            if ((status == OccurrenceExceptionStatus.MOVED) != (movedDay != null)) {
                throw new IllegalArgumentException("movedDay must be set exactly when status is moved");
            }
        }
    }

    public record BirthdayInput(String slug, String displayName, List<String> aliases, String relationship,
                                String memoryPath, Birthday birthday) {
        public BirthdayInput {
            Objects.requireNonNull(slug, "slug");
            Objects.requireNonNull(displayName, "displayName");
            aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
            Objects.requireNonNull(birthday, "birthday");
        }
    }

    /** kind is the canonical entry kind after resolving entry_kinds merges. */
    public record HabitDay(String kind, LocalDate day) {
        public HabitDay {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(day, "day");
        }
    }
}
