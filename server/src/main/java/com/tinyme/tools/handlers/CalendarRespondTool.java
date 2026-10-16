package com.tinyme.tools.handlers;

import com.tinyme.integrations.google.service.CalendarClient;
import com.tinyme.integrations.google.service.GmailConfirmationService;
import com.tinyme.integrations.google.service.GoogleAccountRepository;
import com.tinyme.integrations.google.service.GoogleApiHttpClient;
import com.tinyme.integrations.google.service.GoogleOAuthException;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Component
public class CalendarRespondTool implements ToolHandler {
    private static final Set<String> RESPONSES = Set.of("accepted", "declined", "tentative");
    private final CalendarClient calendar;
    private final GoogleAccountRepository accounts;
    private final GmailConfirmationService confirmations;

    public CalendarRespondTool(CalendarClient calendar, GoogleAccountRepository accounts,
                               GmailConfirmationService confirmations) {
        this.calendar = calendar;
        this.accounts = accounts;
        this.confirmations = confirmations;
    }

    @Override public String name() { return "calendar_respond"; }

    @Override public ToolResult handle(JsonNode input, ToolContext context) {
        try {
            String calendarId = ToolInputs.requiredString(input, "calendar_id").trim();
            String eventId = ToolInputs.requiredString(input, "event_id").trim();
            String response = ToolInputs.requiredString(input, "response").trim().toLowerCase(java.util.Locale.ROOT);
            if (calendarId.length() > 500 || eventId.length() > 500)
                throw new IllegalArgumentException("Calendar and event ids are too long");
            if (!RESPONSES.contains(response))
                throw new IllegalArgumentException("response must be accepted, declined, or tentative");
            var account = accounts.find().filter(row -> "connected".equals(row.status()))
                    .orElseThrow(() -> new GoogleOAuthException("google_not_connected"));
            JsonNode event = calendar.getEvent(calendarId, eventId);
            JsonNode attendees = event.path("attendees");
            boolean isInvite = false;
            if (attendees.isArray()) for (JsonNode attendee : attendees) {
                if (attendee.path("self").asBoolean(false)
                        || account.email().equalsIgnoreCase(attendee.path("email").asString(""))) {
                    isInvite = true;
                    break;
                }
            }
            if (!isInvite) throw new IllegalArgumentException("That event is not an invitation for your Google account");
            String title = CalendarEventsTool.text(event, "summary");
            String start = CalendarEventsTool.eventTime(event.path("start"));
            String end = CalendarEventsTool.eventTime(event.path("end"));
            var id = confirmations.requestCalendar(context.sessionRowId(), context.threadId(), context.toolCallId(),
                    calendarId, eventId, response, title, start, end);
            Map<String, Object> ref = Map.of("confirmation_id", id.toString(), "kind", "calendar_invite");
            return new ToolResult.Ok(Map.of("status", "pending", "event", title == null ? "Calendar invitation" : title,
                    "response", response, "message", "Waiting for the user to confirm this invite response"),
                    "Calendar invite response confirmation required", ref,
                    Map.of("status", "pending", "response", response));
        } catch (IllegalArgumentException invalid) {
            return GmailSearchTool.invalid(invalid.getMessage());
        } catch (GoogleOAuthException failure) {
            return CalendarEventsTool.googleError(failure.reason());
        } catch (IOException failure) {
            return CalendarEventsTool.googleError(failure instanceof GoogleApiHttpClient.GoogleApiException api
                    ? api.reason() : "google_unavailable");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return CalendarEventsTool.googleError("google_unavailable");
        }
    }
}
