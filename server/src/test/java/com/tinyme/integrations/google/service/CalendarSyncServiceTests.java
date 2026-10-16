package com.tinyme.integrations.google.service;

import com.tinyme.domain.plans.entity.PlanEntity;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.model.PlanSource;
import com.tinyme.domain.plans.repository.PlanOccurrenceRepository;
import com.tinyme.domain.plans.repository.PlanRepository;
import com.tinyme.settings.UserZone;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CalendarSyncServiceTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void newTinyMePlanIsCreatedAsGoogleEventAndStoresItsRemoteIdentity() throws Exception {
        CalendarClient calendar = mock(CalendarClient.class);
        CalendarSyncRepository state = mock(CalendarSyncRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        GoogleAccountRepository accounts = connectedAccount();
        PlanEntity plan = mock(PlanEntity.class);
        UUID planId = UUID.randomUUID();
        when(plan.getId()).thenReturn(planId);
        when(plan.getKind()).thenReturn(PlanKind.EVENT);
        when(plan.getSource()).thenReturn(PlanSource.APP);
        when(plan.getTitle()).thenReturn("Lunch");
        when(plan.getZone()).thenReturn("Asia/Tokyo");
        when(plan.getStartDay()).thenReturn(LocalDate.of(2026, 10, 20));
        when(plan.getStartTime()).thenReturn(LocalTime.NOON);
        when(plan.getEndTime()).thenReturn(LocalTime.of(13, 0));
        when(plan.getRepeat()).thenReturn(null);
        when(plan.getGoogleEventId()).thenReturn(null);
        when(state.startRun()).thenReturn(UUID.randomUUID());
        when(state.tinyMeCalendarId()).thenReturn(Optional.of("tiny-calendar"));
        when(state.syncToken(anyString())).thenReturn(Optional.empty());
        when(plans.findMirrorCandidates(any(LocalDate.class))).thenReturn(List.of(plan));
        when(calendar.createEvent(eq("tiny-calendar"), any(JsonNode.class)))
                .thenReturn(JSON.readTree("{\"id\":\"google-event\",\"etag\":\"tag-1\"}"));
        when(calendar.listByPrivateProperty(eq("tiny-calendar"), eq("tinyme_plan_id"), eq(planId.toString()), isNull()))
                .thenReturn(JSON.readTree("{\"items\":[]}"));
        when(calendar.listSyncEvents(eq("tiny-calendar"), isNull(), isNull()))
                .thenReturn(JSON.readTree("{\"items\":[],\"nextSyncToken\":\"sync-1\"}"));
        when(calendar.listCalendars(isNull())).thenReturn(JSON.readTree("{\"items\":[]}"));

        service(calendar, state, plans, accounts).synchronize();

        verify(calendar).createEvent(eq("tiny-calendar"), argThat(body ->
                body.path("summary").asString().equals("Lunch")
                        && body.path("extendedProperties").path("private").path("tinyme_plan_id").asString()
                        .equals(planId.toString())));
        verify(plan).setGoogleMirror("google-event", "tag-1");
        verify(plans).saveMirror(plan);
        verify(state).saveSyncToken("tiny-calendar", "sync-1");
        verify(state).finishRun(any(), eq(true), isNull(), anyMap());
    }

    @Test
    void eventFromAnotherCalendarBecomesReadOnlyGooglePlan() throws Exception {
        CalendarClient calendar = mock(CalendarClient.class);
        CalendarSyncRepository state = mock(CalendarSyncRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        PlanOccurrenceRepository occurrences = mock(PlanOccurrenceRepository.class);
        GoogleAccountRepository accounts = connectedAccount();
        when(state.startRun()).thenReturn(UUID.randomUUID());
        when(state.tinyMeCalendarId()).thenReturn(Optional.of("tiny-calendar"));
        when(state.syncToken(anyString())).thenReturn(Optional.empty());
        when(plans.findMirrorCandidates(any(LocalDate.class))).thenReturn(List.of());
        when(plans.findGoogleImported("primary:event-123")).thenReturn(Optional.empty());
        when(calendar.listSyncEvents(eq("tiny-calendar"), isNull(), isNull()))
                .thenReturn(JSON.readTree("{\"items\":[],\"nextSyncToken\":\"tiny-token\"}"));
        JsonNode calendars = JSON.readTree("{\"items\":[{\"id\":\"tiny-calendar\"},{\"id\":\"primary\",\"primary\":true}]}");
        when(calendar.listCalendars(isNull())).thenReturn(calendars);
        when(calendar.listSyncEvents(eq("primary"), isNull(), isNull())).thenReturn(JSON.readTree("""
                {"items":[{"id":"event-123","etag":"etag-2","summary":"Coffee",
                  "start":{"dateTime":"2026-10-21T10:00:00+09:00","timeZone":"Asia/Tokyo"},
                  "end":{"dateTime":"2026-10-21T11:00:00+09:00","timeZone":"Asia/Tokyo"}}],
                 "nextSyncToken":"primary-token"}
                """));
        when(state.syncToken(anyString())).thenReturn(Optional.empty());

        var service = new CalendarSyncService(calendar, state, plans, occurrences,
                new GoogleCalendarEventMapper(), accounts, Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneId.of("UTC")), userZone(ZoneId.of("Asia/Tokyo")));
        service.synchronize();

        var saved = org.mockito.ArgumentCaptor.forClass(PlanEntity.class);
        verify(plans).saveMirror(saved.capture());
        PlanEntity imported = saved.getValue();
        assertThat(imported.getSource()).isEqualTo(PlanSource.GOOGLE);
        assertThat(imported.getExternalId()).isEqualTo("primary:event-123");
        assertThat(imported.getTitle()).isEqualTo("Coffee");
        assertThat(imported.getStartDay()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(imported.getStartTime()).isEqualTo(LocalTime.of(10, 0));
        verify(state).saveSyncToken("primary", "primary-token");
    }

    @Test
    void consecutiveSyncWithNoChangesDoesNotWriteToGoogle() throws Exception {
        CalendarClient calendar = mock(CalendarClient.class);
        CalendarSyncRepository state = mock(CalendarSyncRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        when(state.startRun()).thenReturn(UUID.randomUUID());
        when(state.tinyMeCalendarId()).thenReturn(Optional.of("tiny-calendar"));
        when(state.syncToken(anyString())).thenReturn(Optional.of("cursor"));
        when(plans.findMirrorCandidates(any(LocalDate.class))).thenReturn(List.of());
        when(plans.findPendingMirrorDeletes()).thenReturn(List.of());
        when(calendar.listCalendars(isNull())).thenReturn(JSON.readTree("{\"items\":[]}"));
        when(calendar.listSyncEvents(anyString(), anyString(), isNull()))
                .thenReturn(JSON.readTree("{\"items\":[],\"nextSyncToken\":\"cursor-2\"}"));

        CalendarSyncService service = service(calendar, state, plans, connectedAccount());
        service.synchronize();
        service.synchronize();

        verify(calendar, never()).createEvent(anyString(), any(JsonNode.class));
        verify(calendar, never()).updateEvent(anyString(), anyString(), any(), any(JsonNode.class));
        verify(calendar, never()).deleteEvent(anyString(), anyString(), any());
    }

    @Test
    void retryAfterLostCreateResponseAdoptsExistingMirrorInsteadOfCreatingDuplicate() throws Exception {
        CalendarClient calendar = mock(CalendarClient.class);
        CalendarSyncRepository state = mock(CalendarSyncRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        PlanEntity plan = mock(PlanEntity.class);
        UUID planId = UUID.randomUUID();
        when(plan.getId()).thenReturn(planId);
        when(plan.getKind()).thenReturn(PlanKind.EVENT);
        when(plan.getSource()).thenReturn(PlanSource.APP);
        when(plan.getTitle()).thenReturn("Lunch");
        when(plan.getZone()).thenReturn("Asia/Tokyo");
        when(plan.getStartDay()).thenReturn(LocalDate.of(2026, 10, 20));
        when(plan.getStartTime()).thenReturn(LocalTime.NOON);
        when(plan.getEndTime()).thenReturn(LocalTime.of(13, 0));
        when(state.startRun()).thenReturn(UUID.randomUUID());
        when(state.tinyMeCalendarId()).thenReturn(Optional.of("tiny-calendar"));
        when(state.syncToken(anyString())).thenReturn(Optional.of("cursor"));
        when(plans.findMirrorCandidates(any(LocalDate.class))).thenReturn(List.of(plan));
        when(plans.findPendingMirrorDeletes()).thenReturn(List.of());
        when(calendar.listCalendars(isNull())).thenReturn(JSON.readTree("{\"items\":[]}"));
        when(calendar.listSyncEvents(anyString(), anyString(), isNull()))
                .thenReturn(JSON.readTree("{\"items\":[],\"nextSyncToken\":\"cursor-2\"}"));
        when(calendar.listByPrivateProperty(eq("tiny-calendar"), eq("tinyme_plan_id"), eq(planId.toString()), isNull()))
                .thenReturn(JSON.readTree("{\"items\":[]}"))
                .thenReturn(JSON.readTree("""
                        {"items":[{"id":"event-created","etag":"tag",
                          "extendedProperties":{"private":{"tinyme_plan_id":"%s"}}}]}
                        """.formatted(planId)));
        when(calendar.createEvent(eq("tiny-calendar"), any(JsonNode.class)))
                .thenThrow(new java.io.IOException("response lost after Google accepted create"));

        CalendarSyncService service = service(calendar, state, plans, connectedAccount());
        service.synchronize();
        service.synchronize();

        verify(calendar, times(1)).createEvent(eq("tiny-calendar"), any(JsonNode.class));
        verify(plan).setGoogleMirror("event-created", "tag");
        verify(plans).saveMirror(plan);
    }

    @Test
    void failedSecondPageOfFullResyncLeavesExistingImportedPlansUntouched() throws Exception {
        CalendarClient calendar = mock(CalendarClient.class);
        CalendarSyncRepository state = mock(CalendarSyncRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        PlanOccurrenceRepository occurrences = mock(PlanOccurrenceRepository.class);
        when(state.startRun()).thenReturn(UUID.randomUUID());
        when(state.tinyMeCalendarId()).thenReturn(Optional.of("tiny-calendar"));
        when(state.syncToken(anyString())).thenReturn(Optional.empty());
        when(plans.findMirrorCandidates(any(LocalDate.class))).thenReturn(List.of());
        when(plans.findPendingMirrorDeletes()).thenReturn(List.of());
        when(plans.findGoogleImported("primary:event-123")).thenReturn(Optional.empty());
        when(calendar.listCalendars(isNull())).thenReturn(JSON.readTree("""
                {"items":[{"id":"primary","primary":true,"accessRole":"owner","selected":true}]}
                """));
        when(calendar.listSyncEvents(eq("tiny-calendar"), isNull(), isNull()))
                .thenReturn(JSON.readTree("{\"items\":[],\"nextSyncToken\":\"tiny-cursor\"}"));
        when(calendar.listSyncEvents(eq("primary"), isNull(), isNull())).thenReturn(JSON.readTree("""
                {"items":[{"id":"event-123","summary":"Coffee",
                  "start":{"dateTime":"2026-10-21T10:00:00+09:00","timeZone":"Asia/Tokyo"},
                  "end":{"dateTime":"2026-10-21T11:00:00+09:00","timeZone":"Asia/Tokyo"}}],
                 "nextPageToken":"page-2"}
                """));
        when(calendar.listSyncEvents(eq("primary"), isNull(), eq("page-2")))
                .thenThrow(new java.io.IOException("page two unavailable"));

        new CalendarSyncService(calendar, state, plans, occurrences, new GoogleCalendarEventMapper(),
                connectedAccount(), Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneId.of("UTC")),
                userZone(ZoneId.of("Asia/Tokyo"))).synchronize();

        verify(plans).saveMirror(argThat(plan -> "primary:event-123".equals(plan.getExternalId())));
        verify(state, never()).cancelImportedCalendarSnapshotExcept(anyString(), anyCollection());
        verify(state, never()).saveSyncToken(eq("primary"), anyString());
    }

    @Test
    void repeatingExceptionMapsEightAmTokyoOccurrenceToTokyoDayOnUtcServer() throws Exception {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            CalendarClient calendar = mock(CalendarClient.class);
            CalendarSyncRepository state = mock(CalendarSyncRepository.class);
            PlanRepository plans = mock(PlanRepository.class);
            PlanOccurrenceRepository occurrences = mock(PlanOccurrenceRepository.class);
            PlanEntity master = mock(PlanEntity.class);
            UUID masterId = UUID.randomUUID();
            when(master.getId()).thenReturn(masterId);
            when(master.getSource()).thenReturn(PlanSource.GOOGLE);
            when(state.startRun()).thenReturn(UUID.randomUUID());
            when(state.tinyMeCalendarId()).thenReturn(Optional.of("tiny-calendar"));
            when(state.syncToken(anyString())).thenReturn(Optional.of("cursor"));
            when(plans.findMirrorCandidates(any(LocalDate.class))).thenReturn(List.of());
            when(plans.findPendingMirrorDeletes()).thenReturn(List.of());
            when(plans.findGoogleImported("primary:series")).thenReturn(Optional.of(master));
            when(calendar.listCalendars(isNull())).thenReturn(JSON.readTree("""
                    {"items":[{"id":"primary","primary":true,"accessRole":"owner","selected":true}]}
                    """));
            when(calendar.listSyncEvents(eq("tiny-calendar"), anyString(), isNull()))
                    .thenReturn(JSON.readTree("{\"items\":[],\"nextSyncToken\":\"tiny-next\"}"));
            when(calendar.listSyncEvents(eq("primary"), anyString(), isNull())).thenReturn(JSON.readTree("""
                    {"items":[{"id":"exception","recurringEventId":"series","status":"cancelled",
                      "originalStartTime":{"dateTime":"2026-10-15T23:00:00Z","timeZone":"Asia/Tokyo"}}],
                     "nextSyncToken":"primary-next"}
                    """));

            new CalendarSyncService(calendar, state, plans, occurrences, new GoogleCalendarEventMapper(),
                    connectedAccount(), Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneId.of("UTC")),
                    userZone(ZoneId.of("Asia/Tokyo"))).synchronize();

            verify(occurrences).save(argThat(row -> row.getDay().equals(LocalDate.of(2026, 10, 16))));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void oneCalendarFailureDoesNotStopOtherSelectedCalendars() throws Exception {
        CalendarClient calendar = mock(CalendarClient.class);
        CalendarSyncRepository state = mock(CalendarSyncRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        when(state.startRun()).thenReturn(UUID.randomUUID());
        when(state.tinyMeCalendarId()).thenReturn(Optional.of("tiny-calendar"));
        when(state.syncToken(anyString())).thenReturn(Optional.of("cursor"));
        when(plans.findMirrorCandidates(any(LocalDate.class))).thenReturn(List.of());
        when(plans.findPendingMirrorDeletes()).thenReturn(List.of());
        when(calendar.listCalendars(isNull())).thenReturn(JSON.readTree("""
                {"items":[{"id":"primary","primary":true},
                          {"id":"owned","accessRole":"owner","selected":true},
                          {"id":"not-selected","accessRole":"owner","selected":false},
                          {"id":"shared","accessRole":"reader","selected":true}]}
                """));
        when(calendar.listSyncEvents(eq("tiny-calendar"), anyString(), isNull()))
                .thenReturn(JSON.readTree("{\"items\":[],\"nextSyncToken\":\"tiny-next\"}"));
        when(calendar.listSyncEvents(eq("primary"), anyString(), isNull()))
                .thenReturn(JSON.readTree("{\"items\":[],\"nextSyncToken\":\"primary-next\"}"));
        when(calendar.listSyncEvents(eq("owned"), anyString(), isNull()))
                .thenThrow(new GoogleApiHttpClient.GoogleApiException(403, "scope_missing"));

        CalendarSyncService service = service(calendar, state, plans, connectedAccount());
        service.synchronize();

        verify(state).saveSyncToken("primary", "primary-next");
        verify(calendar, never()).listSyncEvents(eq("not-selected"), anyString(), any());
        verify(calendar, never()).listSyncEvents(eq("shared"), anyString(), any());
        verify(state).finishRun(any(), eq(false), eq("partial_sync"), argThat(details ->
                details.toString().contains("owned") && details.toString().contains("scope_missing")));
    }

    @Test
    void preconditionFailureSkipsOnlyThatPlanAndContinuesOutboundSync() throws Exception {
        CalendarClient calendar = mock(CalendarClient.class);
        CalendarSyncRepository state = mock(CalendarSyncRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        PlanEntity conflicted = mirrorPlan("conflicted-event");
        PlanEntity other = mirrorPlan("other-event");
        UUID conflictedId = conflicted.getId();
        when(state.startRun()).thenReturn(UUID.randomUUID());
        when(state.tinyMeCalendarId()).thenReturn(Optional.of("tiny-calendar"));
        when(state.syncToken(anyString())).thenReturn(Optional.of("cursor"));
        when(plans.findMirrorCandidates(any(LocalDate.class))).thenReturn(List.of(conflicted, other));
        when(plans.findPendingMirrorDeletes()).thenReturn(List.of());
        when(calendar.listCalendars(isNull())).thenReturn(JSON.readTree("{\"items\":[]}"));
        when(calendar.listSyncEvents(eq("tiny-calendar"), anyString(), isNull()))
                .thenReturn(JSON.readTree("{\"items\":[],\"nextSyncToken\":\"tiny-next\"}"));
        when(calendar.updateEvent(eq("tiny-calendar"), eq("conflicted-event"), eq("old-tag"), any(JsonNode.class)))
                .thenThrow(new GoogleApiHttpClient.GoogleApiException(412, "precondition_failed"));
        when(calendar.updateEvent(eq("tiny-calendar"), eq("other-event"), eq("old-tag"), any(JsonNode.class)))
                .thenReturn(JSON.readTree("{\"id\":\"other-event\",\"etag\":\"new-tag\"}"));

        CalendarSyncService service = service(calendar, state, plans, connectedAccount());
        service.synchronize();

        verify(other).setGoogleMirror("other-event", "new-tag");
        verify(plans).saveMirror(other);
        verify(state).finishRun(any(), eq(false), eq("partial_sync"), argThat(details ->
                details.toString().contains(conflictedId.toString())
                        && details.toString().contains("precondition_failed")));
    }

    private static PlanEntity mirrorPlan(String eventId) {
        PlanEntity plan = mock(PlanEntity.class);
        when(plan.getId()).thenReturn(UUID.randomUUID());
        when(plan.getKind()).thenReturn(PlanKind.EVENT);
        when(plan.getSource()).thenReturn(PlanSource.APP);
        when(plan.getTitle()).thenReturn("Lunch");
        when(plan.getZone()).thenReturn("Asia/Tokyo");
        when(plan.getStartDay()).thenReturn(LocalDate.of(2026, 10, 20));
        when(plan.getStartTime()).thenReturn(LocalTime.NOON);
        when(plan.getEndTime()).thenReturn(LocalTime.of(13, 0));
        when(plan.getGoogleEventId()).thenReturn(eventId);
        when(plan.getGoogleEtag()).thenReturn("old-tag");
        when(plan.getRepeat()).thenReturn(null);
        return plan;
    }

    private static GoogleAccountRepository connectedAccount() {
        GoogleAccountRepository accounts = mock(GoogleAccountRepository.class);
        when(accounts.find()).thenReturn(Optional.of(new GoogleAccountRepository.GoogleAccount(
                "owner@example.test", List.of("calendar"), new byte[]{1}, "connected", Instant.now())));
        return accounts;
    }

    private static CalendarSyncService service(CalendarClient calendar, CalendarSyncRepository state,
                                              PlanRepository plans, GoogleAccountRepository accounts) {
        return new CalendarSyncService(calendar, state, plans, mock(PlanOccurrenceRepository.class),
                new GoogleCalendarEventMapper(), accounts, Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneId.of("UTC")), userZone(ZoneId.of("Asia/Tokyo")));
    }

    private static UserZone userZone(ZoneId zone) {
        UserZone userZone = mock(UserZone.class);
        when(userZone.current()).thenReturn(zone);
        return userZone;
    }
}
