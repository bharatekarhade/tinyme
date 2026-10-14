package com.tinyme.domain.plans.service;

import com.tinyme.domain.entries.model.EntryDay;
import com.tinyme.domain.entries.repository.EntryKindRepository;
import com.tinyme.domain.entries.repository.EntryRepository;
import com.tinyme.domain.people.entity.PersonEntity;
import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.people.repository.PersonRepository;
import com.tinyme.domain.plans.entity.PlanEntity;
import com.tinyme.domain.plans.entity.PlanOccurrenceEntity;
import com.tinyme.domain.plans.entity.PlanOccurrenceId;
import com.tinyme.domain.plans.model.OccurrenceExceptionStatus;
import com.tinyme.domain.plans.model.OccurrenceStatus;
import com.tinyme.domain.plans.model.PlanAddCommand;
import com.tinyme.domain.plans.model.PlanChange;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.Repeat;
import com.tinyme.domain.plans.model.PlanOccurrence;
import com.tinyme.domain.plans.model.PlanQuery;
import com.tinyme.domain.plans.model.PlanSource;
import com.tinyme.domain.plans.model.PlanStatus;
import com.tinyme.domain.plans.model.PlanWriteResult;
import com.tinyme.domain.plans.repository.PlanOccurrenceRepository;
import com.tinyme.domain.plans.repository.PlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PlanService {
    private static final DateTimeFormatter CONTEXT_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private final PlanRepository plans;
    private final PlanOccurrenceRepository occurrences;
    private final PersonRepository people;
    private final EntryRepository entries;
    private final EntryKindRepository kinds;
    private final PlanCalendar calendar;

    public PlanService(PlanRepository plans, PlanOccurrenceRepository occurrences,
                       PersonRepository people, EntryRepository entries,
                       EntryKindRepository kinds) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.occurrences = Objects.requireNonNull(occurrences, "occurrences");
        this.people = Objects.requireNonNull(people, "people");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        this.calendar = new PlanCalendar();
    }

    @Transactional
    public PlanWriteResult add(PlanAddCommand command, ZoneId zone, Instant now) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(now, "now");
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (command.day().isBefore(today)) {
            throw new IllegalArgumentException("day is in the past; log past events with entries_add");
        }
        String habitKind = resolveHabitKind(command.habitKind());
        PlanEntity plan = new PlanEntity(command.kind(), command.title(), command.notes(), command.place(), zone,
                command.day(), command.time(), command.endTime(), command.endDay(), command.repeat(),
                habitKind, command.remindMinutes(), PlanSource.CHAT, null, Set.of());
        plan = plans.save(plan, Set.copyOf(command.people()));
        UUID createdId = plan.getId();

        List<PlanOccurrence> calendarItems = calendar(command.day(), command.day().plusDays(PlanCalendar.MAX_DAYS - 1),
                zone, now, false);
        List<PlanOccurrence> upcoming = calendarItems.stream().filter(item -> createdId.equals(item.planId())).toList();
        List<PlanOccurrence> next = upcoming.stream().limit(3).toList();
        List<String> conflicts = firstConflicts(createdId, upcoming.stream().findFirst().orElse(null), calendarItems);
        return new PlanWriteResult(createdId, plan.getTitle(), next, conflicts, List.of());
    }

    @Transactional(readOnly = true)
    public PlanQueryResult query(PlanQuery query, ZoneId zone, Instant now) {
        Objects.requireNonNull(query, "query");
        List<PlanOccurrence> all = calendar(query.from(), query.to(), zone, now, false);
        List<PlanOccurrence> filtered = all.stream()
                .filter(item -> query.includeDone() || item.status() != OccurrenceStatus.DONE)
                .filter(item -> query.kind() == null || item.kind() == query.kind())
                .filter(item -> matchesText(item, query.text()))
                .filter(item -> matchesPerson(item, query.person()))
                .sorted(Comparator.comparing(PlanOccurrence::day)
                        .thenComparing(PlanOccurrence::startTime, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(PlanOccurrence::title))
                .toList();
        boolean truncated = filtered.size() > PlanQuery.MAX_RESULTS;
        return new PlanQueryResult(truncated ? filtered.subList(0, PlanQuery.MAX_RESULTS) : filtered, truncated);
    }

    /** All calendar items for the app API, which has no agent-tool result cap. */
    @Transactional(readOnly = true)
    public List<PlanOccurrence> upcomingItems(LocalDate from, LocalDate to, ZoneId zone, Instant now) {
        PlanCalendar.validateRange(from, to);
        return calendar(from, to, Objects.requireNonNull(zone, "zone"), Objects.requireNonNull(now, "now"), false);
    }

    /**
     * Days with an upcoming habit occurrence for the requested entry kinds. The card layer only
     * needs the dates; plan status, recurrence, moved occurrences, and merged kinds stay here.
     */
    @Transactional(readOnly = true)
    public Set<LocalDate> habitDays(List<String> requestedKinds, LocalDate from, LocalDate to,
                                   ZoneId zone, Instant now) {
        Objects.requireNonNull(requestedKinds, "requestedKinds");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (requestedKinds.isEmpty() || requestedKinds.size() > 5
                || (requestedKinds.contains("*") && requestedKinds.size() != 1)) {
            throw new IllegalArgumentException("kinds must contain 1 to 5 entry kinds, or only '*'");
        }
        if (to.isBefore(from)) throw new IllegalArgumentException("to must not be before from");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(now, "now");
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (to.isBefore(today)) return Set.of();

        boolean allKinds = requestedKinds.contains("*");
        Map<String, String> resolutions = kinds.resolveAll();
        Set<String> canonicalKinds = allKinds ? Set.of() : requestedKinds.stream()
                .map(kind -> resolutions.getOrDefault(kind, kind)).collect(Collectors.toSet());
        LocalDate first = from.isBefore(today) ? today : from;
        Set<LocalDate> result = new HashSet<>();
        for (LocalDate chunkFrom = first; !chunkFrom.isAfter(to);) {
            LocalDate chunkTo = chunkFrom.plusDays(PlanCalendar.MAX_DAYS - 1L);
            if (chunkTo.isAfter(to)) chunkTo = to;
            calendar(chunkFrom, chunkTo, zone, now, false).stream()
                    .filter(item -> item.habitKind() != null)
                    .filter(item -> item.status() == OccurrenceStatus.UPCOMING)
                    .filter(item -> allKinds || canonicalKinds.contains(item.habitKind()))
                    .map(PlanOccurrence::day)
                    .forEach(result::add);
            chunkFrom = chunkTo.plusDays(1);
        }
        return Set.copyOf(result);
    }

    @Transactional(readOnly = true)
    public String contextLine(ZoneId zone, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        List<PlanOccurrence> items = calendar(today, today.plusDays(1), zone, now, false);
        List<String> parts = new ArrayList<>();
        items.stream().filter(item -> item.day().equals(today) && item.startsAt() != null
                        && (item.status() == OccurrenceStatus.UPCOMING || item.status() == OccurrenceStatus.IN_PROGRESS)
                        && item.endsAt().isAfter(now))
                .limit(4).forEach(item -> parts.add(CONTEXT_TIME.format(item.startTime()) + " " + shortTitle(item)));
        if (parts.size() < 4) {
            items.stream().filter(item -> item.day().equals(today.plusDays(1))
                            && (item.status() == OccurrenceStatus.UPCOMING
                            || item.status() == OccurrenceStatus.IN_PROGRESS))
                    .findFirst().ifPresent(item -> parts.add("tomorrow "
                            + (item.startTime() == null ? "" : CONTEXT_TIME.format(item.startTime()) + " ")
                            + shortTitle(item)));
        }
        if (parts.isEmpty()) return null;
        String line = "upcoming: " + String.join(" · ", parts);
        return line.length() > 200 ? line.substring(0, 197) + "…" : line;
    }

    @Transactional
    public Optional<PlanWriteResult> update(UUID id, PlanChange change, ZoneId zone, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(now, "now");
        PlanEntity plan = plans.findLive(id).orElse(null);
        if (plan == null) return Optional.empty();
        if (plan.getStatus() == PlanStatus.CANCELLED && !(change instanceof PlanChange.Cancel)) {
            throw new IllegalArgumentException("a cancelled plan cannot be changed");
        }

        applyChange(plan, id, change, zone, now);

        PlanEntity updated = plans.save(plan);
        LocalDate from = resultAnchor(change, plan, LocalDate.ofInstant(now, zone));
        LocalDate to = from.plusDays(PlanCalendar.MAX_DAYS - 1);
        List<PlanOccurrence> next = calendar(from, to, zone, now, true).stream()
                .filter(item -> id.equals(item.planId())).limit(3).toList();
        return Optional.of(new PlanWriteResult(id, updated.getTitle(), next, List.of(), List.of()));
    }

    /** Applies the same occurrence state transitions used by plans_update for app quick actions. */
    @Transactional
    public Optional<PlanOccurrence> quickAction(UUID id, LocalDate occurrenceDay, QuickAction action,
                                                ZoneId zone, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurrenceDay, "occurrenceDay");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(now, "now");
        PlanEntity plan = plans.findLive(id).orElse(null);
        if (plan == null) return Optional.empty();
        if (plan.getStatus() == PlanStatus.CANCELLED) {
            throw new PlanStateConflictException("a cancelled plan cannot be changed");
        }

        boolean repeating = plan.getRepeat() != null;
        if (!repeating && !plan.getStartDay().equals(occurrenceDay)) {
            throw new IllegalArgumentException("day is not an occurrence of this plan");
        }
        PlanChange change = switch (action) {
            case DONE -> new PlanChange.Done(repeating ? occurrenceDay : null);
            case UNDO_DONE -> new PlanChange.UndoDone(repeating ? occurrenceDay : null);
            case SKIP -> {
                if (!repeating) throw new IllegalArgumentException("only a repeating plan can be skipped");
                yield new PlanChange.Skip(occurrenceDay);
            }
        };
        Optional<PlanWriteResult> updated = update(id, change, zone, now);
        if (updated.isEmpty()) return Optional.empty();
        PlanOccurrence occurrence = occurrenceOf(id, occurrenceDay, zone, now)
                .orElseThrow(() -> new IllegalStateException("updated occurrence is missing from the calendar"));
        return Optional.of(occurrence);
    }

    /** Moves one scheduled occurrence, preserving its duration through the occurrence exception. */
    @Transactional
    public Optional<PlanOccurrence> moveOccurrence(UUID id, LocalDate occurrenceDay, LocalDate day,
                                                   LocalTime time, ZoneId zone, Instant now) {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(now, "now");
        Optional<PlanWriteResult> result = update(id, new PlanChange.Move(occurrenceDay, day, time), zone, now);
        if (result.isEmpty()) return Optional.empty();
        return occurrenceOf(id, occurrenceDay, zone, now);
    }

    private Optional<PlanOccurrence> occurrenceOf(UUID id, LocalDate originalDay, ZoneId zone, Instant now) {
        PlanOccurrenceEntity exception = occurrences.find(new PlanOccurrenceId(id, originalDay)).orElse(null);
        LocalDate from = originalDay;
        LocalDate to = originalDay;
        if (exception != null && exception.getStatus() == OccurrenceExceptionStatus.MOVED) {
            from = exception.getMovedDay().isBefore(from) ? exception.getMovedDay() : from;
            to = exception.getMovedDay().isAfter(to) ? exception.getMovedDay() : to;
        }
        return calendar(from, to, zone, now, true).stream()
                .filter(item -> id.equals(item.planId()) && originalDay.equals(item.originalDay()))
                .findFirst();
    }

    @Transactional
    public boolean delete(UUID id, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        PlanEntity plan = plans.findLive(id).orElse(null);
        if (plan == null) return false;
        plan.softDelete(now);
        plans.save(plan);
        return true;
    }

    @Transactional
    public boolean restore(UUID id) {
        PlanEntity plan = plans.findAny(Objects.requireNonNull(id, "id")).orElse(null);
        if (plan == null) return false;
        plan.restore();
        plans.save(plan);
        return true;
    }

    private void applyChange(PlanEntity plan, UUID id, PlanChange change, ZoneId zone, Instant now) {
        switch (change) {
            case PlanChange.Done done -> {
                if (plan.getKind() != PlanKind.TASK) throw new IllegalArgumentException("only tasks can be marked done");
                if (plan.getRepeat() == null) {
                    if (done.occurrenceDay() != null) throw new IllegalArgumentException("occurrence_day is only for repeating plans");
                    plan.markDone(now);
                } else {
                    LocalDate day = requireOccurrenceDay(done.occurrenceDay());
                    requireScheduled(plan, day, zone, now);
                    occurrences.find(new PlanOccurrenceId(id, day))
                            .filter(row -> row.getStatus() == OccurrenceExceptionStatus.MOVED)
                            .ifPresentOrElse(
                                    row -> occurrences.save(PlanOccurrenceEntity.doneMoved(plan, day,
                                            row.getMovedDay(), row.getMovedTime(), now)),
                                    () -> occurrences.save(PlanOccurrenceEntity.done(plan, day, now)));
                }
            }
            case PlanChange.UndoDone undo -> {
                if (plan.getKind() != PlanKind.TASK) throw new IllegalArgumentException("only tasks can be marked done");
                if (plan.getRepeat() == null) {
                    if (undo.occurrenceDay() != null) throw new IllegalArgumentException("occurrence_day is only for repeating plans");
                    plan.undoDone();
                } else {
                    LocalDate day = requireOccurrenceDay(undo.occurrenceDay());
                    requireScheduled(plan, day, zone, now);
                    occurrences.find(new PlanOccurrenceId(id, day)).ifPresent(row -> {
                        if (row.getStatus() == OccurrenceExceptionStatus.DONE) {
                            occurrences.delete(row.getId());
                        } else if (row.getStatus() == OccurrenceExceptionStatus.MOVED
                                && row.getCompletedAt() != null) {
                            occurrences.save(PlanOccurrenceEntity.moved(plan, day,
                                    row.getMovedDay(), row.getMovedTime()));
                        }
                    });
                }
            }
            case PlanChange.Skip skip -> {
                if (plan.getRepeat() == null) throw new IllegalArgumentException("only a repeating plan can be skipped by occurrence");
                requireScheduled(plan, skip.occurrenceDay(), zone, now);
                occurrences.save(PlanOccurrenceEntity.skipped(plan, skip.occurrenceDay()));
            }
            case PlanChange.Move move -> {
                if (plan.getRepeat() == null) throw new IllegalArgumentException("only a repeating plan can be moved by occurrence");
                requireScheduled(plan, move.occurrenceDay(), zone, now);
                requireNotPast(move.day(), move.time() != null ? move.time() : plan.getStartTime(), zone, now);
                occurrences.save(PlanOccurrenceEntity.moved(plan, move.occurrenceDay(), move.day(), move.time()));
            }
            case PlanChange.Cancel ignored -> plan.cancel();
            case PlanChange.Edit edit -> edit(plan, edit, zone, now);
        }
    }

    private void edit(PlanEntity plan, PlanChange.Edit edit, ZoneId zone, Instant now) {
        if (edit.fields().contains("occurrence_day")) {
            throw new IllegalArgumentException("To change only one day, use action: move or skip");
        }
        String habitKind = edit.fields().contains("habit_kind")
                ? resolveHabitKind(edit.habitKind()) : plan.getHabitKind();
        PlanKind kind = plan.getKind();
        if (edit.fields().contains("kind")) {
            if (edit.kind() == null) throw new IllegalArgumentException("kind must be event or task");
            kind = edit.kind();
        }
        // A task has no end, so turning an event into one drops its end unless a new one was given.
        boolean dropsEnd = kind == PlanKind.TASK && plan.getKind() != PlanKind.TASK;
        Repeat repeat = edit.fields().contains("repeat") ? edit.repeat() : plan.getRepeat();
        LocalDate day = edit.fields().contains("day") ? edit.day() : plan.getStartDay();
        LocalTime time = edit.fields().contains("time") ? edit.time() : plan.getStartTime();
        // A series keeps its past days whatever its new time, so only a one-off plan can be moved into the past.
        if (repeat == null && (edit.fields().contains("day") || edit.fields().contains("time"))) {
            requireNotPast(day, time, zone, now);
        }
        plan.edit(
                kind,
                edit.fields().contains("title") ? edit.title() : plan.getTitle(),
                edit.fields().contains("notes") ? edit.notes() : plan.getNotes(),
                edit.fields().contains("place") ? edit.place() : plan.getPlace(),
                habitKind,
                edit.fields().contains("remind_minutes") ? edit.remindMinutes() : plan.getRemindMinutes(),
                repeat,
                day,
                time,
                edit.fields().contains("end_time") ? edit.endTime() : dropsEnd ? null : plan.getEndTime(),
                edit.fields().contains("end_day") ? edit.endDay() : dropsEnd ? null : plan.getEndDay());
        Set<String> personSlugs = edit.fields().contains("people")
                ? Set.copyOf(edit.people())
                : plan.getPeople().stream().map(PersonEntity::getSlug).collect(Collectors.toSet());
        plans.save(plan, personSlugs);
    }

    /** The one "not into the past" rule for the app and the agent: timed plans by the minute, untimed by the day. */
    private static void requireNotPast(LocalDate day, LocalTime time, ZoneId zone, Instant now) {
        boolean past = time != null
                ? LocalDateTime.of(day, time).atZone(zone).toInstant().isBefore(now)
                : day.isBefore(LocalDate.ofInstant(now, zone));
        if (past) throw new IllegalArgumentException("a plan cannot be moved into the past");
    }

    /** Normalizes and validates the habit kind shared by plan tools and app endpoints. */
    @Transactional(readOnly = true)
    public String resolveHabitKind(String rawKind) {
        if (rawKind == null) return null;
        String kind = rawKind.strip().toLowerCase(Locale.ROOT);
        if (!kind.matches("^[a-z][a-z0-9_]{1,31}$")) {
            throw new IllegalArgumentException("habit_kind must be a valid entry kind");
        }
        return kinds.resolveExisting(kind).orElseThrow(() -> new IllegalArgumentException(
                "habit_kind must be an existing or card-registered entry kind"));
    }

    private void requireScheduled(PlanEntity plan, LocalDate day, ZoneId zone, Instant now) {
        if (day == null) throw new IllegalArgumentException("occurrence_day is required for this action");
        PlanCalendar.PlanInput input = toInput(plan, kinds.resolveAll());
        boolean scheduled = calendar.expand(day, day, now, zone, List.of(input), List.of(), List.of(), List.of(), true)
                .stream().anyMatch(item -> item.day().equals(day));
        if (!scheduled) throw new IllegalArgumentException("occurrence_day is not a scheduled day for this plan");
    }

    private List<PlanOccurrence> calendar(LocalDate from, LocalDate to, ZoneId zone, Instant now,
                                          boolean includeCancelled) {
        List<PlanEntity> loaded = new ArrayList<>(plans.findForCalendar(from, to));
        List<PlanOccurrenceEntity> movedIntoRange = occurrences.findMovedIntoRange(from, to);
        Set<UUID> ids = new HashSet<>();
        loaded.forEach(plan -> ids.add(plan.getId()));
        movedIntoRange.stream().map(PlanOccurrenceEntity::getPlan).forEach(plan -> {
            if (ids.add(plan.getId())) loaded.add(plan);
        });
        List<PlanOccurrenceEntity> exceptions = occurrences.findRelevant(List.copyOf(ids), from, to);
        Map<String, PlanCalendar.ExceptionInput> exceptionMap = new HashMap<>();
        for (PlanOccurrenceEntity exception : exceptions) {
            exceptionMap.put(exception.getPlan().getId() + ":" + exception.getDay(), new PlanCalendar.ExceptionInput(
                    exception.getPlan().getId(), exception.getDay(), exception.getStatus(), exception.getMovedDay(),
                    exception.getMovedTime(), exception.getCompletedAt()));
        }
        Map<String, String> kindResolutions = kinds.resolveAll();
        List<PlanCalendar.PlanInput> planInputs = loaded.stream()
                .map(plan -> toInput(plan, kindResolutions)).toList();
        List<PlanCalendar.BirthdayInput> birthdayInputs = people.findLiveBirthdays().stream()
                .map(person -> new PlanCalendar.BirthdayInput(person.slug(), person.displayName(), person.aliases(),
                        person.relationship(), person.memoryPath(), person.birthday())).toList();
        Set<String> canonicalHabitKinds = new HashSet<>();
        for (PlanEntity plan : loaded) {
            if (plan.getHabitKind() != null) {
                canonicalHabitKinds.add(kindResolutions.getOrDefault(plan.getHabitKind(), plan.getHabitKind()));
            }
        }
        Map<String, String> canonicalByStoredKind = new HashMap<>();
        for (String canonical : canonicalHabitKinds) {
            kindResolutions.forEach((stored, resolved) -> {
                if (canonical.equals(resolved)) canonicalByStoredKind.put(stored, canonical);
            });
            canonicalByStoredKind.putIfAbsent(canonical, canonical);
        }
        List<PlanCalendar.HabitDay> habitDays = entries.habitDays(List.copyOf(canonicalByStoredKind.keySet()), from, to).stream()
                .map(row -> new PlanCalendar.HabitDay(
                        canonicalByStoredKind.getOrDefault(row.kind(), row.kind()), row.day())).toList();
        return calendar.expand(from, to, now, zone, planInputs, exceptionMap.values(), birthdayInputs,
                habitDays, includeCancelled);
    }

    private PlanCalendar.PlanInput toInput(PlanEntity plan, Map<String, String> kindResolutions) {
        List<PersonSnapshot> personSnapshots = plan.getPeople().stream().map(person -> new PersonSnapshot(
                person.getSlug(), person.getDisplayName(), List.of(person.getAliases()), person.getRelationship(),
                person.getMemoryPath())).toList();
        String habitKind = plan.getHabitKind() == null ? null
                : kindResolutions.getOrDefault(plan.getHabitKind(), plan.getHabitKind());
        return new PlanCalendar.PlanInput(plan.getId(), plan.getKind(), plan.getTitle(), plan.getNotes(), plan.getPlace(),
                plan.getZone(), plan.getStartDay(), plan.getStartTime(), plan.getEndTime(), plan.getEndDay(),
                plan.getRepeat(), habitKind, plan.getStatus(), plan.getCompletedAt(), plan.getSource(), personSnapshots);
    }

    private static LocalDate requireOccurrenceDay(LocalDate day) {
        if (day == null) throw new IllegalArgumentException("occurrence_day is required for this action");
        return day;
    }

    private static LocalDate resultAnchor(PlanChange change, PlanEntity plan, LocalDate today) {
        return switch (change) {
            case PlanChange.Done done -> done.occurrenceDay() == null ? plan.getStartDay() : done.occurrenceDay();
            case PlanChange.UndoDone undo -> undo.occurrenceDay() == null ? plan.getStartDay() : undo.occurrenceDay();
            case PlanChange.Skip skip -> skip.occurrenceDay();
            case PlanChange.Move move -> move.day();
            case PlanChange.Edit edit -> edit.day() == null ? today : edit.day();
            case PlanChange.Cancel ignored -> today;
        };
    }

    private static List<String> firstConflicts(UUID createdId, PlanOccurrence target, List<PlanOccurrence> all) {
        if (target == null || target.startsAt() == null || target.kind() != PlanKind.EVENT) return List.of();
        return all.stream().filter(other -> other.kind() == PlanKind.EVENT && other.startsAt() != null
                        && !createdId.equals(other.planId()) && other.day().equals(target.day())
                        && target.startsAt().isBefore(other.endsAt()) && other.startsAt().isBefore(target.endsAt()))
                .map(PlanOccurrence::title).distinct().toList();
    }

    private static boolean matchesText(PlanOccurrence item, String text) {
        if (text == null) return true;
        String needle = text.toLowerCase(Locale.ROOT);
        return item.title().toLowerCase(Locale.ROOT).contains(needle)
                || (item.place() != null && item.place().toLowerCase(Locale.ROOT).contains(needle));
    }

    private static boolean matchesPerson(PlanOccurrence item, String person) {
        if (person == null) return true;
        String needle = person.toLowerCase(Locale.ROOT);
        return item.people().stream().anyMatch(candidate -> candidate.slug().equalsIgnoreCase(needle)
                || candidate.displayName().equalsIgnoreCase(needle)
                || candidate.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(needle)));
    }

    private static String shortTitle(PlanOccurrence item) {
        String title = item.title();
        return item.place() == null || item.place().isBlank() ? title : title + " (" + item.place() + ")";
    }

    public record PlanQueryResult(List<PlanOccurrence> occurrences, boolean truncated) {
        public PlanQueryResult { occurrences = List.copyOf(occurrences); }
    }

    public enum QuickAction { DONE, UNDO_DONE, SKIP }
}
