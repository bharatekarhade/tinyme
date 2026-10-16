package com.tinyme.domain.plans.repository;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.domain.people.entity.PersonEntity;
import com.tinyme.domain.entries.repository.EntryRepository;
import com.tinyme.domain.plans.entity.PlanEntity;
import com.tinyme.domain.plans.entity.PlanOccurrenceEntity;
import com.tinyme.domain.plans.entity.PlanOccurrenceId;
import com.tinyme.domain.plans.model.OccurrenceExceptionStatus;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.PlanChange;
import com.tinyme.domain.plans.model.PlanQuery;
import com.tinyme.domain.plans.model.PlanSource;
import com.tinyme.domain.plans.model.Repeat;
import com.tinyme.domain.plans.model.RepeatFrequency;
import com.tinyme.domain.plans.service.PlanService;
import com.tinyme.integrations.google.service.CalendarSyncRepository;
import com.tinyme.domain.people.model.Birthday;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.MonthDay;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(AgentDatabaseConfiguration.class)
class PlanRepositoryTests {
    @Autowired PlanRepository plans;
    @Autowired PlanOccurrenceRepository occurrences;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlanService planService;
    @Autowired EntryRepository entries;
    @Autowired CalendarSyncRepository calendarSync;

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Instant NOW = Instant.parse("2026-10-13T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @Test
    @Transactional
    void roundTripsPlanRepeatPeopleAndOccurrenceException() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID personId = jdbc.queryForObject("""
                INSERT INTO people (slug, display_name, memory_path)
                VALUES (?, 'Aiko', ?) RETURNING id
                """, UUID.class, "plan-test-" + suffix, "people/plan-test-" + suffix + ".md");
        PersonEntity person = entityManager.getReference(PersonEntity.class, personId);
        LocalDate startDay = LocalDate.of(2026, 10, 12);
        Repeat repeat = new Repeat(RepeatFrequency.WEEKLY, 2,
                Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                LocalDate.of(2026, 12, 31));
        PlanEntity plan = plans.save(new PlanEntity(PlanKind.EVENT, "Lunch with Aiko", "Catch up",
                "Cafe", ZoneId.of("Asia/Tokyo"), startDay, LocalTime.of(13, 0), LocalTime.of(14, 0),
                null, repeat, null, 15, PlanSource.CHAT, null, Set.of(person)));
        entityManager.flush();
        entityManager.clear();

        assertThat(plan.getId()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT kind FROM plans WHERE id = ?", String.class, plan.getId()))
                .isEqualTo("event");
        assertThat(jdbc.queryForObject("SELECT repeat_freq FROM plans WHERE id = ?", String.class, plan.getId()))
                .isEqualTo("weekly");
        assertThat(jdbc.queryForObject("SELECT repeat_days FROM plans WHERE id = ?", Short.class, plan.getId()))
                .isEqualTo((short) 21);

        PlanEntity reloaded = plans.findLive(plan.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Lunch with Aiko");
        assertThat(reloaded.getZone()).isEqualTo("Asia/Tokyo");
        assertThat(reloaded.getRepeat()).isEqualTo(repeat);
        assertThat(reloaded.getPeople()).extracting(PersonEntity::getId).containsExactly(personId);

        LocalDate occurrenceDay = startDay.plusDays(2);
        PlanOccurrenceEntity moved = occurrences.save(PlanOccurrenceEntity.moved(
                reloaded, occurrenceDay, occurrenceDay.plusDays(1), LocalTime.of(15, 0)));
        entityManager.flush();
        entityManager.clear();
        PlanOccurrenceEntity reloadedOccurrence = occurrences.find(
                new PlanOccurrenceId(plan.getId(), occurrenceDay)).orElseThrow();
        assertThat(reloadedOccurrence.getStatus()).isEqualTo(OccurrenceExceptionStatus.MOVED);
        assertThat(reloadedOccurrence.getMovedDay()).isEqualTo(occurrenceDay.plusDays(1));
        assertThat(reloadedOccurrence.getMovedTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(moved.getId()).isEqualTo(new PlanOccurrenceId(plan.getId(), occurrenceDay));
        assertThat(jdbc.queryForObject("SELECT status FROM plan_occurrences WHERE plan_id = ? AND day = ?",
                String.class, plan.getId(), occurrenceDay)).isEqualTo("moved");
    }

    @Test
    @Transactional
    void completedFullCalendarSnapshotCancelsOnlyImportedEventsNotSeen() {
        String suffix = UUID.randomUUID().toString();
        String kept = "snapshot-calendar:kept-" + suffix;
        String removed = "snapshot-calendar:removed-" + suffix;
        UUID keptId = jdbc.queryForObject("""
                INSERT INTO plans (kind, title, zone, start_day, source, external_id)
                VALUES ('event', 'Keep', 'Asia/Tokyo', '2026-10-20', 'google', ?) RETURNING id
                """, UUID.class, kept);
        UUID removedId = jdbc.queryForObject("""
                INSERT INTO plans (kind, title, zone, start_day, source, external_id)
                VALUES ('event', 'Remove', 'Asia/Tokyo', '2026-10-21', 'google', ?) RETURNING id
                """, UUID.class, removed);

        calendarSync.cancelImportedCalendarSnapshotExcept("snapshot-calendar", Set.of(kept));

        assertThat(jdbc.queryForObject("SELECT status FROM plans WHERE id=?", String.class, keptId))
                .isEqualTo("active");
        assertThat(jdbc.queryForObject("SELECT status FROM plans WHERE id=?", String.class, removedId))
                .isEqualTo("cancelled");
    }

    @Test
    @Transactional
    void mirrorCandidatesIncludeChangedOrUnmirroredOnlyAndKeepOngoingOldRepeats() {
        LocalDate cutoff = LocalDate.of(2026, 9, 16);
        PlanEntity oldOneOff = plans.save(new PlanEntity(PlanKind.EVENT, "Old one-off", null, null,
                TOKYO, cutoff.minusDays(31), LocalTime.NOON, LocalTime.of(13, 0), null,
                null, null, null, PlanSource.APP, null, Set.of()));
        PlanEntity ongoingRepeat = new PlanEntity(PlanKind.EVENT, "Weekly class", null, null,
                TOKYO, LocalDate.of(2020, 1, 1), LocalTime.NOON, LocalTime.of(13, 0), null,
                new Repeat(RepeatFrequency.WEEKLY, 1, Set.of(DayOfWeek.MONDAY), null),
                null, null, PlanSource.APP, null, Set.of());
        ongoingRepeat.setGoogleMirror("remote-event", "etag-1");
        ongoingRepeat = plans.saveMirror(ongoingRepeat);
        entityManager.flush();
        entityManager.clear();

        assertThat(plans.findMirrorCandidates(cutoff)).extracting(PlanEntity::getId)
                .doesNotContain(oldOneOff.getId(), ongoingRepeat.getId());

        jdbc.update("UPDATE plans SET google_sync_at=statement_timestamp() - interval '1 day' WHERE id=?",
                ongoingRepeat.getId());
        entityManager.clear();
        assertThat(plans.findMirrorCandidates(cutoff)).extracting(PlanEntity::getId)
                .contains(ongoingRepeat.getId()).doesNotContain(oldOneOff.getId());
    }

    @Test
    void databaseRejectsInvalidPlanOccurrenceAndBirthdayCombinations() {
        assertPlanRejected("""
                INSERT INTO plans (kind, title, zone, start_day, end_time)
                VALUES ('event', 'missing start', 'UTC', DATE '2026-01-01', TIME '10:00')
                """);
        assertPlanRejected("""
                INSERT INTO plans (kind, title, zone, start_day, start_time, end_time)
                VALUES ('event', 'backwards time', 'UTC', DATE '2026-01-01', TIME '22:00', TIME '01:00')
                """);
        assertPlanRejected("""
                INSERT INTO plans (kind, title, zone, start_day, start_time, end_time, end_day)
                VALUES ('event', 'overnight ends same day', 'UTC', DATE '2026-01-01', TIME '22:00', TIME '01:00', DATE '2026-01-01')
                """);
        assertPlanRejected("""
                INSERT INTO plans (kind, title, zone, start_day, repeat_freq, repeat_until)
                VALUES ('event', 'ends before start', 'UTC', DATE '2026-01-02', 'daily', DATE '2026-01-01')
                """);
        assertPlanRejected("""
                INSERT INTO plans (kind, title, zone, start_day, repeat_freq, repeat_days)
                VALUES ('event', 'unknown weekday bit', 'UTC', DATE '2026-01-01', 'weekly', 128)
                """);
        assertPlanRejected("""
                INSERT INTO plans (kind, title, zone, start_day, end_day, repeat_freq)
                VALUES ('event', 'repeating multiday', 'UTC', DATE '2026-01-01', DATE '2026-01-02', 'daily')
                """);
        assertPlanRejected("""
                INSERT INTO plans (kind, title, zone, start_day, repeat_freq, status, completed_at)
                VALUES ('event', 'whole series done', 'UTC', DATE '2026-01-01', 'daily', 'done', now())
                """);

        UUID planId = jdbc.queryForObject("""
                INSERT INTO plans (kind, title, zone, start_day)
                VALUES ('event', 'constraint parent', 'UTC', DATE '2026-01-01') RETURNING id
                """, UUID.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO plan_occurrences (plan_id, day, status)
                VALUES (?, DATE '2026-01-01', 'done')
                """, planId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO plan_occurrences (plan_id, day, status, completed_at)
                VALUES (?, DATE '2026-01-02', 'skipped', now())
                """, planId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO plan_occurrences (plan_id, day, status, moved_time)
                VALUES (?, DATE '2026-01-03', 'skipped', TIME '10:00')
                """, planId)).isInstanceOf(DataIntegrityViolationException.class);

        UUID personId = jdbc.queryForObject("""
                INSERT INTO people (slug, display_name, memory_path)
                VALUES (?, 'Birthday Constraint', 'people/birthday-constraint.md') RETURNING id
                """, UUID.class, "birthday-" + UUID.randomUUID().toString().substring(0, 8));
        assertThatThrownBy(() -> jdbc.update("UPDATE people SET birthday_year = 2000 WHERE id = ?", personId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE people SET birthday_month = 2, birthday_day = 30 WHERE id = ?
                """, personId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE people SET birthday_month = 4, birthday_day = 31 WHERE id = ?
                """, personId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE people SET birthday_month = 2, birthday_day = 29, birthday_year = 2025 WHERE id = ?
                """, personId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> new Birthday(MonthDay.of(2, 29), 2025))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void calendarQueryFindsOverdueActiveTaskButNotDoneTask() {
        PlanEntity active = plan(PlanKind.TASK, "Overdue active", LocalDate.of(2026, 10, 1), null,
                null, null, null);
        PlanEntity done = plan(PlanKind.TASK, "Overdue done", LocalDate.of(2026, 10, 2), null,
                null, null, null);
        plans.save(active);
        plans.save(done);
        entityManager.flush();
        jdbc.update("UPDATE plans SET status = 'done', completed_at = now() WHERE id = ?", done.getId());
        entityManager.flush();
        entityManager.clear();

        List<PlanEntity> found = plans.findForCalendar(TODAY, TODAY.plusDays(1));

        assertThat(found).extracting(PlanEntity::getId).contains(active.getId()).doesNotContain(done.getId());
    }

    @Test
    @Transactional
    void repeatingPlanSkipMoveCancelAndEditUseDatabaseExceptionsAndQueries() {
        PlanEntity plan = plan(PlanKind.EVENT, "Repeating gym", LocalDate.of(2026, 10, 12),
                LocalTime.of(19, 0), null, null,
                new Repeat(RepeatFrequency.DAILY, 1, Set.of(), LocalDate.of(2026, 10, 31)));
        plans.save(plan);

        planService.update(plan.getId(), new PlanChange.Skip(LocalDate.of(2026, 10, 13)), TOKYO, NOW);
        var afterSkip = planService.query(new PlanQuery(TODAY, TODAY, null, null, null, true), TOKYO, NOW);
        assertThat(afterSkip.occurrences()).singleElement()
                .satisfies(item -> assertThat(item.status().name()).isEqualTo("SKIPPED"));

        planService.update(plan.getId(), new PlanChange.Move(LocalDate.of(2026, 10, 14),
                LocalDate.of(2026, 10, 20), LocalTime.of(20, 0)), TOKYO, NOW);
        var moved = planService.query(new PlanQuery(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20),
                null, null, null, true), TOKYO, NOW);
        assertThat(moved.occurrences()).anySatisfy(item -> {
            assertThat(item.originalDay()).isEqualTo(LocalDate.of(2026, 10, 14));
            assertThat(item.day()).isEqualTo(LocalDate.of(2026, 10, 20));
            assertThat(item.startTime()).isEqualTo(LocalTime.of(20, 0));
        });

        planService.update(plan.getId(), new PlanChange.Cancel(), TOKYO, NOW);
        assertThat(planService.query(new PlanQuery(TODAY, TODAY.plusDays(3), null, null, null, true), TOKYO, NOW)
                .occurrences()).isEmpty();

        PlanEntity editable = plan(PlanKind.TASK, "Old schedule", LocalDate.of(2026, 10, 12), null,
                null, null, new Repeat(RepeatFrequency.WEEKLY, 1, Set.of(DayOfWeek.MONDAY), null));
        plans.save(editable);
        planService.update(editable.getId(), new PlanChange.Edit(Set.of("day", "repeat"), null,
                LocalDate.of(2026, 10, 14), null, null, null, null, List.of(), null,
                new Repeat(RepeatFrequency.WEEKLY, 1, Set.of(DayOfWeek.WEDNESDAY), null), null, null, null), TOKYO, NOW);
        assertThat(planService.query(new PlanQuery(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14),
                null, null, null, true), TOKYO, NOW).occurrences()).singleElement()
                .satisfies(item -> assertThat(item.day()).isEqualTo(LocalDate.of(2026, 10, 14)));
    }

    @Test
    @Transactional
    void movedOccurrenceFromOutsideRangeAndNativeHabitDaysResolveAgainstPostgres() {
        PlanEntity endedSeries = plan(PlanKind.EVENT, "Old series", LocalDate.of(2026, 10, 1),
                LocalTime.of(9, 0), null, null,
                new Repeat(RepeatFrequency.DAILY, 1, Set.of(), LocalDate.of(2026, 10, 5)));
        plans.save(endedSeries);
        planService.update(endedSeries.getId(), new PlanChange.Move(LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 15), LocalTime.of(12, 0)), TOKYO, NOW);
        var moved = planService.query(new PlanQuery(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18),
                null, null, null, true), TOKYO, NOW);
        assertThat(moved.occurrences()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("Old series");
            assertThat(item.originalDay()).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(item.day()).isEqualTo(LocalDate.of(2026, 10, 15));
        });

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String canonical = "habit_" + suffix;
        String merged = "old_" + suffix;
        jdbc.update("INSERT INTO entry_kinds (kind) VALUES (?)", canonical);
        jdbc.update("INSERT INTO entry_kinds (kind, merged_into) VALUES (?, ?)", merged, canonical);
        PlanEntity habit = plan(PlanKind.TASK, "Habit", TODAY, null, null, canonical, null);
        plans.save(habit);
        jdbc.update("INSERT INTO entries (ts, local_day, kind) VALUES (?, ?, ?)",
                java.time.OffsetDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC), TODAY, merged);
        entityManager.flush();
        entityManager.clear();

        var completed = planService.query(new PlanQuery(TODAY, TODAY, null, null, null, true), TOKYO, NOW);
        assertThat(completed.occurrences()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("Habit");
            assertThat(item.status().name()).isEqualTo("DONE");
        });
        assertThat(entries.habitDays(List.of(merged), TODAY, TODAY)).hasSize(1);
    }

    @Test
    @Transactional
    void addReportsOverlappingEventAndContextSkipsDoneButKeepsInProgress() {
        PlanEntity overlap = plan(PlanKind.EVENT, "Existing lunch", LocalDate.of(2026, 10, 16),
                LocalTime.of(13, 30), LocalTime.of(14, 30), null, null);
        plans.save(overlap);
        var created = planService.add(new com.tinyme.domain.plans.model.PlanAddCommand(PlanKind.EVENT,
                "Lunch", LocalDate.of(2026, 10, 16), LocalTime.of(13, 0), LocalTime.of(14, 0), null,
                null, List.of(), null, null, null, null), TOKYO, NOW);
        assertThat(created.conflicts()).contains("Existing lunch");

        PlanEntity gym = plan(PlanKind.TASK, "Gym", TODAY, LocalTime.of(19, 30), null, null,
                new Repeat(RepeatFrequency.DAILY, 1, Set.of(), null));
        PlanEntity current = plan(PlanKind.EVENT, "Current visit", TODAY, LocalTime.of(9, 0),
                LocalTime.of(11, 0), null, null);
        PlanEntity tomorrow = plan(PlanKind.EVENT, "All-day event", TODAY.plusDays(1), null, null, null, null);
        plans.save(gym);
        plans.save(current);
        plans.save(tomorrow);
        planService.update(gym.getId(), new PlanChange.Skip(TODAY), TOKYO, NOW);

        String line = planService.contextLine(TOKYO, NOW);
        assertThat(line).contains("09:00 Current visit").contains("tomorrow All-day event").doesNotContain("Gym");
    }

    @Test
    @Transactional
    void changingASeriesTimeKeepsItsStartDayAndWeekdayEvenWhenTodaysTimeHasPassed() {
        LocalDate lastTuesday = TODAY.minusDays(7);
        PlanEntity run = plans.save(plan(PlanKind.EVENT, "Run", lastTuesday, LocalTime.of(18, 0),
                LocalTime.of(18, 30), null, new Repeat(RepeatFrequency.WEEKLY, 1, Set.of(), null)));

        planService.update(run.getId(), new PlanChange.Edit(Set.of("time", "end_time"), null, null,
                LocalTime.of(8, 0), LocalTime.of(8, 30), null, null, List.of(), null, null, null, null, null), TOKYO, NOW);

        PlanEntity saved = plans.findLive(run.getId()).orElseThrow();
        assertThat(saved.getStartDay()).isEqualTo(lastTuesday);
        assertThat(saved.getStartTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(planService.query(new PlanQuery(TODAY, TODAY.plusDays(6), null, null, null, true), TOKYO, NOW)
                .occurrences()).singleElement()
                .satisfies(item -> {
                    assertThat(item.day()).isEqualTo(TODAY);
                    assertThat(item.startTime()).isEqualTo(LocalTime.of(8, 0));
                });
    }

    @Test
    @Transactional
    void nothingCanBeMovedIntoThePastByEditOrByMove() {
        PlanEntity dentist = plans.save(plan(PlanKind.EVENT, "Dentist", TODAY.plusDays(1), LocalTime.of(10, 0),
                LocalTime.of(11, 0), null, null));
        assertThatThrownBy(() -> planService.update(dentist.getId(), new PlanChange.Edit(Set.of("day", "time"), null,
                TODAY, LocalTime.of(9, 0), null, null, null, List.of(), null, null, null, null, null), TOKYO, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a plan cannot be moved into the past");

        PlanEntity standup = plans.save(plan(PlanKind.EVENT, "Standup", TODAY, LocalTime.of(11, 0),
                null, null, new Repeat(RepeatFrequency.DAILY, 1, Set.of(), null)));
        assertThatThrownBy(() -> planService.update(standup.getId(),
                new PlanChange.Move(TODAY.plusDays(1), TODAY, LocalTime.of(9, 0)), TOKYO, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a plan cannot be moved into the past");
        assertThatThrownBy(() -> planService.update(standup.getId(),
                new PlanChange.Move(TODAY.plusDays(1), TODAY.minusDays(1), null), TOKYO, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a plan cannot be moved into the past");
    }

    @Test
    @Transactional
    void kindCanBeEditedAndATaskDropsTheEventsEnd() {
        PlanEntity call = plans.save(plan(PlanKind.EVENT, "Call the bank", TODAY.plusDays(1), LocalTime.of(10, 0),
                LocalTime.of(10, 30), null, null));

        planService.update(call.getId(), new PlanChange.Edit(Set.of("kind"), null, null, null, null, null,
                null, List.of(), null, null, null, null, PlanKind.TASK), TOKYO, NOW);

        PlanEntity saved = plans.findLive(call.getId()).orElseThrow();
        assertThat(saved.getKind()).isEqualTo(PlanKind.TASK);
        assertThat(saved.getStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(saved.getEndTime()).isNull();
    }

    private PlanEntity plan(PlanKind kind, String title, LocalDate day, LocalTime time,
                            LocalTime endTime, String habitKind, Repeat repeat) {
        return new PlanEntity(kind, title, null, null, TOKYO, day, time, endTime, null, repeat,
                habitKind, null, PlanSource.CHAT, null, Set.of());
    }

    private void assertPlanRejected(String sql) {
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
