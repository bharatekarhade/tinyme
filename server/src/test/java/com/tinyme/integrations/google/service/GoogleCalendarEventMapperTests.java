package com.tinyme.integrations.google.service;

import com.tinyme.domain.plans.entity.PlanEntity;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.PlanSource;
import com.tinyme.domain.plans.model.Repeat;
import com.tinyme.domain.plans.model.RepeatFrequency;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCalendarEventMapperTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final GoogleCalendarEventMapper mapper = new GoogleCalendarEventMapper();

    @Test
    void planMapsWallClockTimeRepeatAndPrivatePlanIdentity() {
        PlanEntity plan = new PlanEntity(PlanKind.EVENT, "Gym", "Strength", "Local gym",
                ZoneId.of("Asia/Tokyo"), LocalDate.of(2026, 10, 19), LocalTime.of(19, 30),
                LocalTime.of(20, 30), null,
                new Repeat(RepeatFrequency.WEEKLY, 1, Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), null),
                null, null, PlanSource.APP, null, Set.of());
        var id = java.util.UUID.randomUUID();
        try {
            var field = PlanEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(plan, id);
        } catch (ReflectiveOperationException impossible) {
            throw new AssertionError(impossible);
        }
        var body = mapper.toGoogle(plan);

        assertThat(body.path("summary").asString()).isEqualTo("Gym");
        assertThat(body.path("start").path("dateTime").asString()).startsWith("2026-10-19T19:30:00");
        assertThat(body.path("start").path("timeZone").asString()).isEqualTo("Asia/Tokyo");
        assertThat(body.path("recurrence").get(0).stringValue()).isEqualTo("RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,WE");
        assertThat(body.path("extendedProperties").path("private").path("tinyme_plan_id").asString())
                .isEqualTo(id.toString());
    }

    @Test
    void googleEventMapsBackToTimedOrAllDayPlanAndUnsupportedRulesAreDetectable() {
        var timed = mapper.fromGoogle(JSON.readTree("""
                {"summary":"Lunch","description":"With Aiko","location":"Cafe","start":{"dateTime":"2026-10-20T12:00:00+09:00","timeZone":"Asia/Tokyo"},
                 "end":{"dateTime":"2026-10-20T13:00:00+09:00"},"recurrence":["RRULE:FREQ=WEEKLY;BYDAY=TU"]}
                """), ZoneId.of("UTC"));
        assertThat(timed.title()).isEqualTo("Lunch");
        assertThat(timed.startDay()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(timed.startTime()).isEqualTo(LocalTime.NOON);
        assertThat(timed.zone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(timed.repeat().days()).containsExactly(DayOfWeek.TUESDAY);

        var allDay = mapper.fromGoogle(JSON.readTree("""
                {"summary":"Trip","start":{"date":"2026-10-20"},"end":{"date":"2026-10-23"}}
                """), ZoneId.of("Asia/Tokyo"));
        assertThat(allDay.allDay()).isTrue();
        assertThat(allDay.endDay()).isEqualTo(LocalDate.of(2026, 10, 22));

        assertThat(mapper.parseRepeat(JSON.readTree("""
                {"recurrence":["RRULE:FREQ=WEEKLY;BYMONTH=3"]}
                """))).isNull();
    }

    @Test
    void repeatingOccurrenceOriginalDayUsesUserZoneEvenWhenServerRunsInUtc() throws Exception {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            var exception = JSON.readTree("""
                    {"originalStartTime":{"dateTime":"2026-10-15T23:00:00Z","timeZone":"Asia/Tokyo"}}
                    """);
            assertThat(mapper.originalStartDay(exception, ZoneId.of("Asia/Tokyo")))
                    .isEqualTo("2026-10-16");
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
