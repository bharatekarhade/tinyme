package com.tinyme.domain.plans.service;

import com.tinyme.domain.people.model.Birthday;
import com.tinyme.domain.plans.model.OccurrenceExceptionStatus;
import com.tinyme.domain.plans.model.OccurrenceStatus;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.PlanOccurrence;
import com.tinyme.domain.plans.model.PlanSource;
import com.tinyme.domain.plans.model.PlanStatus;
import com.tinyme.domain.plans.model.Repeat;
import com.tinyme.domain.plans.model.RepeatFrequency;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanCalendarTests {
    private static final PlanCalendar CALENDAR = new PlanCalendar();
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @Test
    void expandsOneOffAndMultiDayEventWhenItsStartIsBeforeTheRange() {
        var spanning = plan(PlanKind.EVENT, "Conference", LocalDate.of(2026, 10, 12),
                LocalTime.of(9, 0), LocalTime.of(17, 0), LocalDate.of(2026, 10, 15), null, null, PlanStatus.ACTIVE);
        var result = expand(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 16),
                List.of(spanning), List.of(), List.of(), List.of(), false);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().day()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(result.getFirst().endDay()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(result.getFirst().startsAt()).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"));
    }

    @Test
    void expandsDailyRepeatsAtIntervalAndIncludesUntilDay() {
        var repeat = new Repeat(RepeatFrequency.DAILY, 2, Set.of(), LocalDate.of(2026, 10, 19));
        var plan = plan(PlanKind.TASK, "Medicine", LocalDate.of(2026, 10, 13), null, null, null,
                repeat, null, PlanStatus.ACTIVE);

        assertThat(days(expand(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 20),
                List.of(plan), List.of(), List.of(), List.of(), false)))
                .containsExactly(date("2026-10-13"), date("2026-10-15"), date("2026-10-17"), date("2026-10-19"));
    }

    @Test
    void expandsWeeklyDaysAndAnchorsIntervalsToMonday() {
        var weekdays = new Repeat(RepeatFrequency.WEEKLY, 1,
                Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), null);
        var intervalTwo = new Repeat(RepeatFrequency.WEEKLY, 2, Set.of(DayOfWeek.MONDAY), null);
        var first = plan(PlanKind.EVENT, "Run", date("2026-10-01"), LocalTime.of(7, 30), null, null,
                weekdays, null, PlanStatus.ACTIVE);
        var second = plan(PlanKind.TASK, "Laundry", date("2026-10-05"), null, null, null,
                intervalTwo, null, PlanStatus.ACTIVE);

        assertThat(days(expand(date("2026-10-01"), date("2026-10-31"), List.of(first), List.of(), List.of(), List.of(), false)))
                .containsExactly(date("2026-10-02"), date("2026-10-05"), date("2026-10-07"), date("2026-10-09"),
                        date("2026-10-12"), date("2026-10-14"), date("2026-10-16"), date("2026-10-19"),
                        date("2026-10-21"), date("2026-10-23"), date("2026-10-26"), date("2026-10-28"), date("2026-10-30"));
        assertThat(days(expand(date("2026-10-05"), date("2026-10-26"), List.of(second), List.of(), List.of(), List.of(), false)))
                .containsExactly(date("2026-10-05"), date("2026-10-19"));
    }

    @Test
    void monthlyThirtyFirstClampsToTheLastDayAndYearlyLeapDayClamps() {
        var monthly = plan(PlanKind.TASK, "Rent", date("2026-01-31"), null, null, null,
                new Repeat(RepeatFrequency.MONTHLY, 1, Set.of(), null), null, PlanStatus.ACTIVE);
        var leap = plan(PlanKind.TASK, "Birthday", date("2024-02-29"), null, null, null,
                new Repeat(RepeatFrequency.YEARLY, 1, Set.of(), null), null, PlanStatus.ACTIVE);

        assertThat(List.of(date("2026-10-31"), date("2026-11-30"), date("2026-12-31")))
                .containsExactlyElementsOf(List.of(
                        days(expand(date("2026-10-01"), date("2026-10-31"), List.of(monthly), List.of(), List.of(), List.of(), false)).getFirst(),
                        days(expand(date("2026-11-01"), date("2026-11-30"), List.of(monthly), List.of(), List.of(), List.of(), false)).getFirst(),
                        days(expand(date("2026-12-01"), date("2026-12-31"), List.of(monthly), List.of(), List.of(), List.of(), false)).getFirst()));
        assertThat(List.of(2025, 2026, 2027, 2028).stream()
                .map(year -> days(expand(LocalDate.of(year, 2, 1), LocalDate.of(year, 2, 1).withDayOfMonth(
                                LocalDate.of(year, 2, 1).lengthOfMonth()),
                        List.of(leap), List.of(), List.of(), List.of(), false)).getFirst()).toList())
                .containsExactly(date("2025-02-28"), date("2026-02-28"), date("2027-02-28"), date("2028-02-29"));
    }

    @Test
    void skippedAndMovedExceptionsSuppressOriginalAndCanMoveInFromOutsideRange() {
        var plan = plan(PlanKind.EVENT, "Gym", date("2026-10-05"), LocalTime.of(18, 0), LocalTime.of(19, 0), null,
                new Repeat(RepeatFrequency.WEEKLY, 1, Set.of(DayOfWeek.MONDAY), null), null, PlanStatus.ACTIVE);
        var skipped = new PlanCalendar.ExceptionInput(plan.id(), date("2026-10-12"),
                OccurrenceExceptionStatus.SKIPPED, null, null, null);
        var movedOut = new PlanCalendar.ExceptionInput(plan.id(), date("2026-10-19"),
                OccurrenceExceptionStatus.MOVED, date("2026-10-26"), LocalTime.of(20, 0), null);
        var movedIn = new PlanCalendar.ExceptionInput(plan.id(), date("2026-10-05"),
                OccurrenceExceptionStatus.MOVED, date("2026-10-20"), LocalTime.of(20, 0), null);

        var result = expand(date("2026-10-12"), date("2026-10-26"), List.of(plan),
                List.of(skipped, movedOut, movedIn), List.of(), List.of(), false);

        assertThat(result).extracting(PlanOccurrence::id).containsExactly(
                plan.id() + ":2026-10-12", plan.id() + ":2026-10-05", plan.id() + ":2026-10-26",
                plan.id() + ":2026-10-19");
        assertThat(result).filteredOn(o -> o.status() == OccurrenceStatus.SKIPPED).hasSize(1);
        assertThat(result).filteredOn(o -> o.day().equals(date("2026-10-20"))).singleElement()
                .satisfies(o -> {
                    assertThat(o.originalDay()).isEqualTo(date("2026-10-05"));
                    assertThat(o.startTime()).isEqualTo(LocalTime.of(20, 0));
                });
        assertThat(result).filteredOn(o -> o.day().equals(date("2026-10-26"))
                        && o.originalDay().equals(date("2026-10-19"))).singleElement()
                .satisfies(o -> assertThat(o.startTime()).isEqualTo(LocalTime.of(20, 0)));

        assertThat(expand(date("2026-10-19"), date("2026-10-19"), List.of(plan),
                List.of(movedOut), List.of(), List.of(), false)).isEmpty();
    }

    @Test
    void computesStatusesForTimedEventsTasksHabitsAndCancelledPlans() {
        var inProgress = plan(PlanKind.EVENT, "Now", TODAY, LocalTime.of(18, 30), LocalTime.of(20, 0), null,
                null, null, PlanStatus.ACTIVE);
        var ended = plan(PlanKind.EVENT, "Past event", TODAY, LocalTime.of(17, 0), LocalTime.of(18, 0), null,
                null, null, PlanStatus.ACTIVE);
        var overdue = plan(PlanKind.TASK, "Old task", date("2026-10-10"), null, null, null,
                null, null, PlanStatus.ACTIVE);
        var habit = plan(PlanKind.TASK, "Workout", TODAY, null, null, null,
                null, "workout", PlanStatus.ACTIVE);
        var missedHabit = plan(PlanKind.TASK, "Yesterday workout", date("2026-10-12"), null, null, null,
                null, "workout", PlanStatus.ACTIVE);
        var cancelled = plan(PlanKind.EVENT, "Cancelled", TODAY, null, null, null,
                null, null, PlanStatus.CANCELLED);
        var doneException = new PlanCalendar.ExceptionInput(overdue.id(), date("2026-10-10"),
                OccurrenceExceptionStatus.DONE, null, null, NOW);
        var result = expand(TODAY, TODAY, List.of(inProgress, ended, overdue, habit, missedHabit, cancelled),
                List.of(doneException), List.of(), List.of(new PlanCalendar.HabitDay("workout", TODAY)), false);

        assertThat(result).extracting(PlanOccurrence::title)
                .containsExactly("Old task", "Yesterday workout", "Workout", "Past event", "Now");
        assertThat(result).extracting(PlanOccurrence::status)
                .containsExactly(OccurrenceStatus.DONE, OccurrenceStatus.MISSED, OccurrenceStatus.DONE,
                        OccurrenceStatus.MISSED, OccurrenceStatus.IN_PROGRESS);

        var withCancelled = expand(TODAY, TODAY, List.of(cancelled), List.of(), List.of(), List.of(), true);
        assertThat(withCancelled).singleElement().extracting(PlanOccurrence::status).isEqualTo(OccurrenceStatus.CANCELLED);
    }

    @Test
    void overdueTasksAreIncludedAndMarkedMissed() {
        var overdue = plan(PlanKind.TASK, "Call dentist", date("2026-10-09"), null, null, null,
                null, null, PlanStatus.ACTIVE);
        var result = expand(TODAY, date("2026-10-14"), List.of(overdue), List.of(), List.of(), List.of(), false);
        assertThat(result).singleElement().satisfies(o -> {
            assertThat(o.day()).isEqualTo(date("2026-10-09"));
            assertThat(o.status()).isEqualTo(OccurrenceStatus.MISSED);
        });
    }

    @Test
    void birthdaysBecomeAllDayEventsAndIncludeAgeWhenKnown() {
        var birthday = new PlanCalendar.BirthdayInput("kenji", "Kenji", List.of("Ken"),
                "friend", "people/kenji.md", new Birthday(MonthDay.of(10, 14), 1990));
        var result = expand(date("2026-10-14"), date("2026-10-14"), List.of(), List.of(),
                List.of(birthday), List.of(), false);

        assertThat(result).singleElement().satisfies(o -> {
            assertThat(o.title()).isEqualTo("Kenji's birthday (turns 36)");
            assertThat(o.allDay()).isTrue();
            assertThat(o.source()).isEqualTo("people");
            assertThat(o.people()).singleElement().satisfies(person -> {
                assertThat(person.slug()).isEqualTo("kenji");
                assertThat(person.aliases()).containsExactly("Ken");
            });
        });
    }

    @Test
    void scheduledWallClockTimeSurvivesDstTransition() {
        var berlin = ZoneId.of("Europe/Berlin");
        var plan = new PlanCalendar.PlanInput(UUID.randomUUID(), PlanKind.EVENT, "Weekly run", null, null,
                berlin.getId(), date("2026-03-23"), LocalTime.of(7, 30), null, null,
                new Repeat(RepeatFrequency.WEEKLY, 1, Set.of(DayOfWeek.MONDAY), null), null,
                PlanStatus.ACTIVE, null, PlanSource.CHAT, List.of());
        var result = CALENDAR.expand(date("2026-03-23"), date("2026-03-30"),
                Instant.parse("2026-03-22T12:00:00Z"), berlin, List.of(plan), List.of(), List.of(), List.of(), false);

        assertThat(result).extracting(PlanOccurrence::startTime)
                .containsExactly(LocalTime.of(7, 30), LocalTime.of(7, 30));
        assertThat(result).extracting(PlanOccurrence::startsAt)
                .containsExactly(Instant.parse("2026-03-23T06:30:00Z"), Instant.parse("2026-03-30T05:30:00Z"));
    }

    @Test
    void acceptsAtMost62DaysAndRejectsLongerRanges() {
        var from = date("2026-10-01");
        assertThat(CALENDAR.expand(from, from.plusDays(61), NOW, TOKYO, List.of(), List.of(), List.of(), List.of(), false))
                .isEmpty();
        assertThatThrownBy(() -> CALENDAR.expand(from, from.plusDays(62), NOW, TOKYO,
                List.of(), List.of(), List.of(), List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("62 days");
    }

    private static List<PlanOccurrence> expand(LocalDate from, LocalDate to, List<PlanCalendar.PlanInput> plans,
                                               List<PlanCalendar.ExceptionInput> exceptions,
                                               List<PlanCalendar.BirthdayInput> birthdays,
                                               List<PlanCalendar.HabitDay> habitDays,
                                               boolean includeCancelled) {
        return CALENDAR.expand(from, to, NOW, TOKYO, plans, exceptions, birthdays, habitDays, includeCancelled);
    }

    private static PlanCalendar.PlanInput plan(PlanKind kind, String title, LocalDate startDay,
                                                LocalTime startTime, LocalTime endTime, LocalDate endDay,
                                                Repeat repeat, String habitKind, PlanStatus status) {
        return new PlanCalendar.PlanInput(UUID.randomUUID(), kind, title, null, null, TOKYO.getId(), startDay,
                startTime, endTime, endDay, repeat, habitKind, status, null, PlanSource.CHAT, List.of());
    }

    private static List<LocalDate> days(List<PlanOccurrence> occurrences) {
        return occurrences.stream().map(PlanOccurrence::day).toList();
    }

    private static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }
}
