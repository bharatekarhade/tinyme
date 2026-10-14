package com.tinyme.tools.handlers;

import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.people.model.get.PeopleLookup;
import com.tinyme.domain.people.model.get.PersonMatch;
import com.tinyme.domain.people.model.get.PersonMatchType;
import com.tinyme.domain.people.service.PersonService;
import com.tinyme.domain.plans.model.PlanOccurrence;
import com.tinyme.domain.plans.model.PlanChange;
import com.tinyme.domain.plans.model.PlanQuery;
import com.tinyme.domain.plans.model.PlanWriteResult;
import com.tinyme.domain.plans.service.PlanService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlansToolTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ID = UUID.fromString("a38a14cb-8d3b-41a4-9b82-37bd3a7c8e11");
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    private static final ToolContext CTX = new ToolContext(UUID.randomUUID(), UUID.randomUUID(), ZONE,
            LocalDate.of(2026, 10, 13), NOW);

    private final PlanService service = mock(PlanService.class);
    private final PersonService people = mock(PersonService.class);

    @Test
    void addResolvesPeopleAndReturnsPlanReference() throws Exception {
        PersonSnapshot aiko = new PersonSnapshot("aiko", "Aiko", List.of(), "friend", "people/aiko.md");
        when(people.get("Aiko")).thenReturn(new PeopleLookup(PersonMatchType.EXACT, List.of(new PersonMatch(aiko, null))));
        when(service.add(any(), eq(ZONE), eq(NOW))).thenReturn(new PlanWriteResult(ID, "Lunch with Aiko",
                List.of(occurrence(ID, LocalDate.of(2026, 10, 16), LocalTime.of(13, 0))), List.of(), List.of()));

        ToolResult.Ok result = (ToolResult.Ok) new PlansAddTool(service, people).handle(JSON.readTree("""
                {"kind":"event","title":"Lunch with Aiko","day":"2026-10-16","time":"13:00","people":["Aiko"]}
                """), CTX);

        assertThat(result.summary()).isEqualTo("Planned · Lunch with Aiko · Fri 1:00 pm");
        assertThat(result.ref()).containsEntry("plan_id", ID.toString());
        verify(service).add(org.mockito.ArgumentMatchers.argThat(command -> command.people().equals(List.of("aiko"))),
                eq(ZONE), eq(NOW));
    }

    @Test
    void addRejectsUnknownPeople() throws Exception {
        when(people.get("Aiko")).thenReturn(new PeopleLookup(PersonMatchType.NONE, List.of()));
        ToolResult.Err unknown = (ToolResult.Err) new PlansAddTool(service, people).handle(JSON.readTree("""
                {"kind":"event","title":"Lunch","day":"2026-10-16","people":["Aiko"]}
                """), CTX);
        assertThat(unknown.code()).isEqualTo("validation_error");
        assertThat(unknown.message()).contains("call people_upsert first");
        verify(service, never()).add(any(), any(), any());
    }

    @Test
    void queryRequiresBoundedDateRangeAndMapsOccurrences() throws Exception {
        when(service.query(any(), eq(ZONE), eq(NOW))).thenReturn(new PlanService.PlanQueryResult(
                List.of(occurrence(ID, LocalDate.of(2026, 10, 16), LocalTime.of(13, 0))), false));

        ToolResult.Ok result = (ToolResult.Ok) new PlansQueryTool(service).handle(JSON.readTree("""
                {"from":"2026-10-12","to":"2026-10-18","q":"lunch"}
                """), CTX);
        assertThat(JSON.valueToTree(result.data()).get("occurrences").size()).isEqualTo(1);
        verify(service).query(eq(new PlanQuery(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18),
                null, "lunch", null, false)), eq(ZONE), eq(NOW));

        ToolResult.Err invalid = (ToolResult.Err) new PlansQueryTool(service).handle(JSON.readTree("""
                {"from":"2026-01-01","to":"2026-03-31"}
                """), CTX);
        assertThat(invalid.code()).isEqualTo("validation_error");
    }

    @Test
    void updateRequiresOccurrenceDayForSkipAndReturnsNotFound() throws Exception {
        PlansUpdateTool tool = new PlansUpdateTool(service, people);
        ToolResult.Err missingDay = (ToolResult.Err) tool.handle(JSON.readTree("""
                {"id":"a38a14cb-8d3b-41a4-9b82-37bd3a7c8e11","action":"skip"}
                """), CTX);
        assertThat(missingDay.code()).isEqualTo("validation_error");
        assertThat(missingDay.message()).contains("occurrence_day is required");

        when(service.update(eq(ID), any(), eq(ZONE), eq(NOW))).thenReturn(Optional.empty());
        ToolResult.Err missing = (ToolResult.Err) tool.handle(JSON.readTree("""
                {"id":"a38a14cb-8d3b-41a4-9b82-37bd3a7c8e11","action":"cancel"}
                """), CTX);
        assertThat(missing.code()).isEqualTo("not_found");
    }

    @Test
    void moveTimeOnlyDefaultsToTheOriginalOccurrenceDay() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 13);
        when(service.update(eq(ID), any(), eq(ZONE), eq(NOW))).thenReturn(Optional.of(
                new PlanWriteResult(ID, "Gym", List.of(), List.of(), List.of())));

        ToolResult.Ok result = (ToolResult.Ok) new PlansUpdateTool(service, people).handle(JSON.readTree("""
                {"id":"a38a14cb-8d3b-41a4-9b82-37bd3a7c8e11", "action":"move",
                 "occurrence_day":"2026-10-13", "time":"20:00"}
                """), CTX);

        assertThat(result.summary()).isEqualTo("Moved · Gym");
        verify(service).update(eq(ID), eq(new PlanChange.Move(day, day, LocalTime.of(20, 0))), eq(ZONE), eq(NOW));
    }

    @Test
    void moveWithNoChangedDayOrTimeIsRejected() throws Exception {
        ToolResult.Err result = (ToolResult.Err) new PlansUpdateTool(service, people).handle(JSON.readTree("""
                {"id":"a38a14cb-8d3b-41a4-9b82-37bd3a7c8e11", "action":"move",
                 "occurrence_day":"2026-10-13"}
                """), CTX);

        assertThat(result.code()).isEqualTo("validation_error");
        assertThat(result.message()).isEqualTo("nothing to move: give a new day or time");
        verify(service, never()).update(any(), any(), any(), any());
    }

    @Test
    void kindIsAnEditableField() throws Exception {
        when(service.update(eq(ID), any(), eq(ZONE), eq(NOW))).thenReturn(Optional.of(
                new PlanWriteResult(ID, "Call the bank", List.of(), List.of(), List.of())));

        new PlansUpdateTool(service, people).handle(JSON.readTree("""
                {"id":"a38a14cb-8d3b-41a4-9b82-37bd3a7c8e11", "kind":"task"}
                """), CTX);

        verify(service).update(eq(ID), org.mockito.ArgumentMatchers.argThat(change ->
                change instanceof PlanChange.Edit edit && edit.fields().equals(java.util.Set.of("kind"))
                        && edit.kind() == com.tinyme.domain.plans.model.PlanKind.TASK), eq(ZONE), eq(NOW));
    }

    private static PlanOccurrence occurrence(UUID id, LocalDate day, LocalTime time) {
        return new PlanOccurrence(id + ":" + day, id, day, day, day, time, null, null,
                NOW, NOW.plusSeconds(3600), false, com.tinyme.domain.plans.model.PlanKind.EVENT,
                "Lunch", null, null, List.of(), com.tinyme.domain.plans.model.OccurrenceStatus.UPCOMING,
                null, null, "chat");
    }
}
