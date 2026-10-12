package com.tinyme.domain.plans.repository;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.domain.people.entity.PersonEntity;
import com.tinyme.domain.plans.entity.PlanEntity;
import com.tinyme.domain.plans.entity.PlanOccurrenceEntity;
import com.tinyme.domain.plans.entity.PlanOccurrenceId;
import com.tinyme.domain.plans.model.OccurrenceExceptionStatus;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.PlanSource;
import com.tinyme.domain.plans.model.Repeat;
import com.tinyme.domain.plans.model.RepeatFrequency;
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

    private void assertPlanRejected(String sql) {
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
