package com.tinyme.tools.handlers;

import com.tinyme.integrations.google.service.CalendarClient;
import com.tinyme.integrations.google.service.GmailConfirmationService;
import com.tinyme.integrations.google.service.CalendarSyncRepository;
import com.tinyme.integrations.google.service.GoogleAccountRepository;
import com.tinyme.tools.model.ToolContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CalendarToolsTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void eventsReturnsOtherCalendarEventsAndOmitsTinyMePlans() throws Exception {
        CalendarClient calendar = mock(CalendarClient.class);
        when(calendar.listCalendars(isNull())).thenReturn(JSON.readTree("""
                {"items":[{"id":"primary","summary":"Personal"}]}
                """));
        when(calendar.listEvents(eq("primary"), any(), any(), isNull())).thenReturn(JSON.readTree("""
                {"items":[
                  {"id":"e1","summary":"Lunch","start":{"dateTime":"2026-10-20T12:00:00+09:00"},"end":{"dateTime":"2026-10-20T13:00:00+09:00"},"status":"confirmed"},
                  {"id":"e2","summary":"TinyMe plan","start":{"dateTime":"2026-10-20T14:00:00+09:00"},"end":{"dateTime":"2026-10-20T15:00:00+09:00"},"extendedProperties":{"private":{"tinyme_plan_id":"p1"}}}
                ]}
                """));
        var result = (com.tinyme.tools.model.ToolResult.Ok) new CalendarEventsTool(calendar).handle(
                JSON.readTree("{\"from\":\"2026-10-20T00:00:00+09:00\",\"to\":\"2026-10-21T00:00:00+09:00\"}"), context());
        Map<?, ?> data = (Map<?, ?>) result.data();
        assertThat(data.get("events").toString()).contains("Lunch").doesNotContain("TinyMe plan");
        assertThat(result.summary()).isEqualTo("1 calendar event");
    }

    @Test
    void freeTimeFindsGapsLongEnoughForRequestedMeeting() throws Exception {
        CalendarClient calendar = mock(CalendarClient.class);
        when(calendar.listCalendars(isNull())).thenReturn(JSON.readTree("""
                {"items":[{"id":"primary","accessRole":"owner","selected":true},
                          {"id":"shared","accessRole":"reader","selected":true},
                          {"id":"unselected","accessRole":"owner","selected":false}]}
                """));
        when(calendar.freeBusy(any(), any(), eq(List.of("primary")), anyString())).thenReturn(JSON.readTree("""
                {"calendars":{"primary":{"busy":[
                  {"start":"2026-10-20T09:00:00Z","end":"2026-10-20T10:00:00Z"},
                  {"start":"2026-10-20T11:00:00Z","end":"2026-10-20T11:30:00Z"}
                ]}}}
                """));
        var result = (com.tinyme.tools.model.ToolResult.Ok) new CalendarFreeTimeTool(calendar).handle(
                JSON.readTree("{\"from\":\"2026-10-20T08:00:00Z\",\"to\":\"2026-10-20T12:00:00Z\",\"duration_minutes\":45}"), context());
        Map<?, ?> data = (Map<?, ?>) result.data();
        assertThat(data.get("slots").toString()).contains("08:00:00Z", "10:00:00Z");
        assertThat((List<?>) data.get("slots")).hasSize(2);
        verify(calendar).freeBusy(any(), any(), eq(List.of("primary")), anyString());
    }

    @Test
    void eventsPagesCalendarsAndEventsAndSkipsTinyMeCalendarById() throws Exception {
        CalendarClient calendar = mock(CalendarClient.class);
        CalendarSyncRepository state = mock(CalendarSyncRepository.class);
        when(state.tinyMeCalendarId()).thenReturn(java.util.Optional.of("tiny"));
        when(calendar.listCalendars(isNull())).thenReturn(JSON.readTree("""
                {"items":[{"id":"tiny","summary":"TinyMe"},{"id":"primary","summary":"Personal"}],"nextPageToken":"c2"}
                """));
        when(calendar.listCalendars(eq("c2"))).thenReturn(JSON.readTree("""
                {"items":[{"id":"work","summary":"Work"}]}
                """));
        when(calendar.listEvents(eq("primary"), any(), any(), isNull())).thenReturn(JSON.readTree("""
                {"items":[{"id":"later","summary":"Later","start":{"dateTime":"2026-10-20T12:00:00Z"}}],"nextPageToken":"e2"}
                """));
        when(calendar.listEvents(eq("primary"), any(), any(), eq("e2"))).thenReturn(JSON.readTree("""
                {"items":[{"id":"early","summary":"Early","start":{"dateTime":"2026-10-20T08:00:00Z"}}]}
                """));
        when(calendar.listEvents(eq("work"), any(), any(), isNull())).thenReturn(JSON.readTree("""
                {"items":[{"id":"middle","summary":"Middle","start":{"dateTime":"2026-10-20T10:00:00Z"}}]}
                """));

        var result = (com.tinyme.tools.model.ToolResult.Ok) new CalendarEventsTool(calendar, state).handle(
                JSON.readTree("{\"from\":\"2026-10-20T00:00:00Z\",\"to\":\"2026-10-21T00:00:00Z\"}"), context());
        assertThat(result.data().toString()).contains("Early", "Middle", "Later");
        verify(calendar, never()).listEvents(eq("tiny"), any(), any(), any());
    }

    @Test
    void eventsRejectRangesLongerThanSixtyTwoDays() throws Exception {
        CalendarClient calendar = mock(CalendarClient.class);
        var result = new CalendarEventsTool(calendar).handle(JSON.readTree(
                "{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-04-01T00:00:00Z\"}"), context());
        assertThat(result).isInstanceOf(com.tinyme.tools.model.ToolResult.Err.class)
                .extracting("message").asString().contains("62 days");
        verifyNoInteractions(calendar);
    }

    @Test
    void inviteResponseCreatesConfirmationWithoutSendingResponse() throws Exception {
        CalendarClient calendar = mock(CalendarClient.class);
        GoogleAccountRepository accounts = mock(GoogleAccountRepository.class);
        GmailConfirmationService confirmations = mock(GmailConfirmationService.class);
        UUID confirmationId = UUID.randomUUID();
        when(accounts.find()).thenReturn(java.util.Optional.of(new GoogleAccountRepository.GoogleAccount(
                "owner@example.test", List.of(), new byte[]{1}, "connected", Instant.now())));
        when(calendar.getEvent("primary", "invite-1")).thenReturn(JSON.readTree("""
                {"summary":"Project sync","start":{"dateTime":"2026-10-20T10:00:00Z"},
                 "end":{"dateTime":"2026-10-20T11:00:00Z"},"attendees":[{"email":"owner@example.test","self":true}]}
                """));
        when(confirmations.requestCalendar(any(), any(), any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(confirmationId);
        var result = (com.tinyme.tools.model.ToolResult.Ok) new CalendarRespondTool(calendar, accounts, confirmations).handle(
                JSON.readTree("{\"calendar_id\":\"primary\",\"event_id\":\"invite-1\",\"response\":\"accepted\"}"), context());
        assertThat(result.summary()).isEqualTo("Calendar invite response confirmation required");
        assertThat(result.ref().get("confirmation_id")).isEqualTo(confirmationId.toString());
        verify(confirmations).requestCalendar(any(), any(), any(), eq("primary"), eq("invite-1"), eq("accepted"),
                eq("Project sync"), eq("2026-10-20T10:00:00Z"), eq("2026-10-20T11:00:00Z"));
        verify(calendar, never()).respondToInvite(anyString(), anyString(), anyString(), anyString());
    }

    private static ToolContext context() {
        return new ToolContext(UUID.randomUUID(), UUID.randomUUID(), ZoneId.of("Asia/Tokyo"),
                LocalDate.of(2026, 10, 15), Instant.parse("2026-10-15T00:00:00Z"));
    }
}
