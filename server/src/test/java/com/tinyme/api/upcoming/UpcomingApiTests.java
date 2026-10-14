package com.tinyme.api.upcoming;

import com.tinyme.auth.service.DeviceTokenService;
import com.tinyme.domain.plans.model.PlanAddCommand;
import com.tinyme.domain.plans.model.PlanChange;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.Repeat;
import com.tinyme.domain.plans.model.RepeatFrequency;
import com.tinyme.domain.plans.service.PlanService;
import com.tinyme.settings.UserZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@AutoConfigureMockMvc
@Import(UpcomingApiTests.DatabaseConfiguration.class)
class UpcomingApiTests {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final Instant NOW = Instant.parse("2026-10-13T03:15:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceTokenService tokens;
    @Autowired PlanService plans;
    @Autowired UserZone userZone;

    @AfterEach
    void cleanPlans() {
        jdbc.update("DELETE FROM plans WHERE title LIKE 'upcoming-api-%'");
        jdbc.update("DELETE FROM people WHERE slug LIKE 'upcoming-api-%'");
    }

    @Test
    void upcomingRequiresAuthenticationAndDefaultsToSevenDays() throws Exception {
        mvc.perform(get("/upcoming")).andExpect(status().isUnauthorized());

        String bearer = bearer("upcoming-defaults");
        mvc.perform(get("/upcoming").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.now").value(NOW.toString()))
                .andExpect(jsonPath("$.today").value("2026-10-13"))
                .andExpect(jsonPath("$.from").value("2026-10-13"))
                .andExpect(jsonPath("$.to").value("2026-10-19"))
                .andExpect(jsonPath("$.days.length()").value(7));
    }

    @Test
    void upcomingBuildsNextUpOverdueAndBirthdayItems() throws Exception {
        ZoneId zone = userZone.current();
        LocalTime eventTime = LocalTime.of(13, 45);
        plans.add(command(PlanKind.EVENT, "upcoming-api-lunch", TODAY, eventTime, LocalTime.of(14, 30), null),
                zone, NOW);
        plans.add(command(PlanKind.TASK, "upcoming-api-water", TODAY, null, null, null), zone, NOW);

        jdbc.update("""
                INSERT INTO plans (kind, title, zone, start_day)
                VALUES ('task', 'upcoming-api-overdue', ?, DATE '2026-10-10')
                """, zone.getId());
        String slug = "upcoming-api-birthday-" + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("""
                INSERT INTO people (slug, display_name, memory_path, birthday_month, birthday_day)
                VALUES (?, 'Upcoming API birthday', ?, 10, 14)
                """, slug, "people/" + slug + ".md");

        mvc.perform(get("/upcoming").header("Authorization", bearer("upcoming-data")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next_up.title").value("upcoming-api-lunch"))
                .andExpect(jsonPath("$.next_up.start_time").value("13:45"))
                .andExpect(jsonPath("$.overdue[0].title").value("upcoming-api-overdue"))
                .andExpect(jsonPath("$.days[0].count").value(2))
                .andExpect(jsonPath("$.days[0].has_timed").value(true))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[2].source").value("people"))
                .andExpect(jsonPath("$.items[2].editable").value(false));
    }

    @Test
    void nextUpIsBasedOnTodayEvenWhenViewingNextWeek() throws Exception {
        plans.add(command(PlanKind.EVENT, "upcoming-api-in-progress", TODAY,
                LocalTime.of(11, 30), LocalTime.of(13, 0), null), userZone.current(), NOW);

        mvc.perform(get("/upcoming").header("Authorization", bearer("upcoming-next-week"))
                        .param("from", "2026-10-19").param("to", "2026-10-25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-19"))
                .andExpect(jsonPath("$.next_up.title").value("upcoming-api-in-progress"))
                .andExpect(jsonPath("$.next_up.status").value("in_progress"));
    }

    @Test
    void pastRangeDoesNotDuplicateMissedTasksInOverdue() throws Exception {
        jdbc.update("""
                INSERT INTO plans (kind, title, zone, start_day)
                VALUES ('task', 'upcoming-api-past-task', 'Asia/Tokyo', DATE '2026-10-10')
                """);

        mvc.perform(get("/upcoming").header("Authorization", bearer("upcoming-past-range"))
                        .param("from", "2026-10-06").param("to", "2026-10-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue.length()").value(0))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("upcoming-api-past-task"));
    }

    @Test
    void rangeLimitAndQuickActionsUsePlanServiceTransitions() throws Exception {
        String bearer = bearer("upcoming-actions");
        String auth = "Authorization";
        var task = plans.add(command(PlanKind.TASK, "upcoming-api-done", TODAY, null, null, null),
                userZone.current(), NOW);

        mvc.perform(get("/upcoming").header(auth, bearer).param("from", "2026-10-01").param("to", "2026-12-02"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/plans/{id}/occurrences/{day}/done", task.id(), TODAY)
                        .header(auth, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"));
        mvc.perform(delete("/plans/{id}/occurrences/{day}/done", task.id(), TODAY)
                        .header(auth, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("upcoming"));

        var repeating = plans.add(command(PlanKind.TASK, "upcoming-api-repeat", TODAY, null, null,
                new Repeat(RepeatFrequency.DAILY, 1, Set.of(), null)), userZone.current(), NOW);
        mvc.perform(post("/plans/{id}/occurrences/{day}/skip", repeating.id(), TODAY)
                        .header(auth, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skipped"));
        var repeatingDelete = plans.add(command(PlanKind.TASK, "upcoming-api-delete-one", TODAY, null, null,
                new Repeat(RepeatFrequency.DAILY, 1, Set.of(), null)), userZone.current(), NOW);
        mvc.perform(delete("/plans/{id}/occurrences/{day}", repeatingDelete.id(), TODAY)
                        .header(auth, bearer))
                .andExpect(status().isNoContent());
        mvc.perform(get("/upcoming").header(auth, bearer)
                        .param("from", TODAY.toString()).param("to", TODAY.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + repeatingDelete.id() + ":2026-10-13')]" ).doesNotExist())
                .andExpect(jsonPath("$.items[?(@.id == '" + repeatingDelete.id() + ":2026-10-14')]" ).exists());
        mvc.perform(delete("/plans/{id}/occurrences/{day}", repeatingDelete.id(), TODAY)
                        .header(auth, bearer))
                .andExpect(status().isNoContent());
        mvc.perform(post("/plans/{id}/occurrences/{day}/done", repeating.id(), TODAY.minusDays(1))
                        .header(auth, bearer))
                .andExpect(status().isBadRequest());

        var moved = plans.add(command(PlanKind.TASK, "upcoming-api-moved", TODAY, null, null,
                new Repeat(RepeatFrequency.DAILY, 1, Set.of(), null)), userZone.current(), NOW);
        // Moved to a day that has since passed. Nothing can be moved into the past, so the row is written directly.
        jdbc.update("INSERT INTO plan_occurrences (plan_id, day, status, moved_day) VALUES (?, ?, 'moved', ?)",
                moved.id(), TODAY, TODAY.minusDays(1));
        mvc.perform(post("/plans/{id}/occurrences/{day}/done", moved.id(), TODAY)
                        .header(auth, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(moved.id() + ":" + TODAY))
                .andExpect(jsonPath("$.day").value(TODAY.minusDays(1).toString()))
                .andExpect(jsonPath("$.status").value("done"));
        mvc.perform(get("/upcoming").header(auth, bearer)
                        .param("from", TODAY.minusDays(1).toString()).param("to", TODAY.minusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(moved.id() + ":" + TODAY))
                .andExpect(jsonPath("$.items[0].status").value("done"));
        mvc.perform(delete("/plans/{id}/occurrences/{day}/done", moved.id(), TODAY)
                        .header(auth, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day").value(TODAY.minusDays(1).toString()))
                .andExpect(jsonPath("$.status").value("missed"));

        jdbc.update("INSERT INTO entry_kinds (kind) VALUES ('workout') ON CONFLICT (kind) DO NOTHING");
        var habit = plans.add(new PlanAddCommand(PlanKind.EVENT, "upcoming-api-habit", TODAY,
                        LocalTime.of(18, 0), null, null, null, List.of(), null, null, "workout", null),
                userZone.current(), NOW);
        mvc.perform(post("/plans/{id}/occurrences/{day}/done", habit.id(), TODAY)
                        .header(auth, bearer))
                .andExpect(status().isBadRequest());

        var cancelled = plans.add(command(PlanKind.TASK, "upcoming-api-cancelled", TODAY, null, null, null),
                userZone.current(), NOW);
        plans.update(cancelled.id(), new PlanChange.Cancel(), userZone.current(), NOW);
        mvc.perform(post("/plans/{id}/occurrences/{day}/done", cancelled.id(), TODAY)
                        .header(auth, bearer))
                .andExpect(status().isConflict());

        mvc.perform(delete("/plans/{id}", task.id()).header(auth, bearer))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/plans/{id}", task.id()).header(auth, bearer))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/plans/{id}", UUID.randomUUID()).header(auth, bearer))
                .andExpect(status().isNotFound());
        mvc.perform(post("/plans/{id}/occurrences/{day}/skip", UUID.randomUUID(), TODAY)
                        .header(auth, bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void calendarMoveAndUndoDeleteUsePlanService() throws Exception {
        String auth = bearer("upcoming-calendar-move");
        var repeating = plans.add(command(PlanKind.EVENT, "upcoming-api-move", TODAY,
                        LocalTime.of(15, 0), LocalTime.of(16, 0),
                        new Repeat(RepeatFrequency.DAILY, 1, Set.of(), null)), userZone.current(), NOW);

        mvc.perform(post("/plans/{id}/occurrences/{day}/move", repeating.id(), TODAY)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"day":"2026-10-13","time":"19:00","end_time":"20:30"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.start_time").value("19:00"))
                .andExpect(jsonPath("$.end_time").value("20:30"));

        mvc.perform(post("/plans/{id}/occurrences/{day}/move", repeating.id(), TODAY)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"day":"2026-10-13","time":"11:00"}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/plans/{id}/occurrences/{day}/move", repeating.id(), TODAY)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"day":"2026-10-13","time":"16:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.start_time").value("16:00"))
                .andExpect(jsonPath("$.end_time").value("17:00"));

        mvc.perform(patch("/plans/{id}", repeating.id())
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"day":"2026-10-13","time":"17:00","end_time":"18:00","end_day":null}
                                """))
                .andExpect(status().isNoContent());
        mvc.perform(get("/upcoming").header("Authorization", auth)
                        .param("from", TODAY.plusDays(1).toString()).param("to", TODAY.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].start_time").value("17:00"));

        // Without a day only the time changes, and an earlier time than now today is fine for a series.
        mvc.perform(patch("/plans/{id}", repeating.id())
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"time":"09:00","end_time":"10:00"}
                                """))
                .andExpect(status().isNoContent());
        mvc.perform(get("/upcoming").header("Authorization", auth)
                        .param("from", TODAY.plusDays(1).toString()).param("to", TODAY.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].start_time").value("09:00"))
                .andExpect(jsonPath("$.items[0].end_time").value("10:00"));

        var oneOff = plans.add(command(PlanKind.EVENT, "upcoming-api-undo", TODAY,
                LocalTime.of(19, 0), LocalTime.of(20, 0), null), userZone.current(), NOW);
        mvc.perform(delete("/plans/{id}", oneOff.id()).header("Authorization", auth))
                .andExpect(status().isNoContent());
        mvc.perform(post("/plans/{id}/restore", oneOff.id()).header("Authorization", auth))
                .andExpect(status().isNoContent());
        mvc.perform(get("/upcoming").header("Authorization", auth)
                        .param("from", TODAY.toString()).param("to", TODAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.plan_id == '%s')]".formatted(oneOff.id())).exists());
    }

    private String bearer(String device) {
        return "Bearer " + tokens.issue(device).token();
    }

    private static PlanAddCommand command(PlanKind kind, String title, LocalDate day, LocalTime time,
                                          LocalTime end, Repeat repeat) {
        return new PlanAddCommand(kind, title, day, time, end, null, null, List.of(), null, repeat, null, null);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));
        }

        @Bean
        @org.springframework.context.annotation.Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
