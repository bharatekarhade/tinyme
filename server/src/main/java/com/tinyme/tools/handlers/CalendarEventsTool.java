package com.tinyme.tools.handlers;

import com.tinyme.integrations.google.service.CalendarClient;
import com.tinyme.integrations.google.service.CalendarSyncRepository;
import com.tinyme.integrations.google.service.GoogleApiHttpClient;
import com.tinyme.integrations.google.service.GoogleOAuthException;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CalendarEventsTool implements ToolHandler {
    private final CalendarClient calendar;
    private final CalendarSyncRepository syncState;

    @Autowired
    public CalendarEventsTool(CalendarClient calendar, CalendarSyncRepository syncState) {
        this.calendar = calendar;
        this.syncState = syncState;
    }

    CalendarEventsTool(CalendarClient calendar) { this(calendar, null); }
    @Override public String name() { return "calendar_events"; }

    @Override public ToolResult handle(JsonNode input, ToolContext context) {
        try {
            Instant from = parseInstant(ToolInputs.requiredString(input, "from"));
            Instant to = parseInstant(ToolInputs.requiredString(input, "to"));
            if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to");
            if (Duration.between(from, to).compareTo(Duration.ofDays(62)) > 0)
                throw new IllegalArgumentException("Calendar event range cannot exceed 62 days");
            List<Map<String, Object>> events = new ArrayList<>();
            String tinyMeId = syncState == null ? null : syncState.tinyMeCalendarId().orElse(null);
            String calendarPage = null;
            do {
                JsonNode listPage = calendar.listCalendars(calendarPage);
                JsonNode lists = listPage.path("items");
                if (lists.isArray()) for (JsonNode item : lists) {
                    String id = text(item, "id");
                    if (id == null || id.equals(tinyMeId) || item.path("deleted").asBoolean(false)) continue;
                    String calendarName = text(item, "summary");
                    String eventPage = null;
                    do {
                        JsonNode response = calendar.listEvents(id, from, to, eventPage);
                        JsonNode rows = response.path("items");
                        if (rows.isArray()) for (JsonNode row : rows) {
                            if ("cancelled".equals(text(row, "status")) || isTinyMePlan(row)) continue;
                            Map<String, Object> value = new LinkedHashMap<>();
                            put(value, "event_id", text(row, "id"));
                            put(value, "calendar_id", id);
                            put(value, "calendar", calendarName);
                            put(value, "title", text(row, "summary"));
                            put(value, "start", eventTime(row.path("start")));
                            put(value, "end", eventTime(row.path("end")));
                            put(value, "location", text(row, "location"));
                            put(value, "status", text(row, "status"));
                            events.add(value);
                        }
                        eventPage = text(response, "nextPageToken");
                    } while (eventPage != null);
                }
                calendarPage = text(listPage, "nextPageToken");
            } while (calendarPage != null);
            events.sort(Comparator.comparing(event -> (String) event.getOrDefault("start", "")));
            if (events.size() > 100) events = new ArrayList<>(events.subList(0, 100));
            return new ToolResult.Ok(Map.of("events", events, "count", events.size()),
                    events.size() + (events.size() == 1 ? " calendar event" : " calendar events"), Map.of(),
                    Map.of("count", events.size(), "event_ids", events.stream()
                            .map(row -> row.get("event_id")).filter(String.class::isInstance).toList()));
        } catch (IllegalArgumentException invalid) {
            return GmailSearchTool.invalid(invalid.getMessage());
        } catch (GoogleOAuthException failure) {
            return googleError(failure.reason());
        } catch (IOException failure) {
            return googleError(failure instanceof GoogleApiHttpClient.GoogleApiException api
                    ? api.reason() : "google_unavailable");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return googleError("google_unavailable");
        }
    }

    static Instant parseInstant(String value) {
        try { return Instant.parse(value); }
        catch (RuntimeException ignored) { return OffsetDateTime.parse(value).toInstant(); }
    }

    static String eventTime(JsonNode node) {
        String dateTime = text(node, "dateTime");
        return dateTime != null ? dateTime : text(node, "date");
    }

    static boolean isTinyMePlan(JsonNode event) {
        return event.path("extendedProperties").path("private").has("tinyme_plan_id");
    }

    static ToolResult.Err googleError(String reason) {
        return switch (reason == null ? "" : reason) {
            case "google_not_connected" -> new ToolResult.Err("google_not_connected", "Connect Google to use Calendar.");
            case "google_reauth_required" -> new ToolResult.Err("google_reauth_required", "Reconnect your Google account to use Calendar.");
            case "scope_missing" -> new ToolResult.Err("scope_missing", "Calendar access is not allowed. Reconnect Google and allow Calendar.");
            case "api_not_enabled" -> new ToolResult.Err("api_not_enabled", "The Calendar API is disabled for TinyMe's Google Cloud project. Enable Google Calendar API in Google Cloud Console, then try again.");
            default -> new ToolResult.Err("google_unavailable", "Google Calendar is unavailable right now. Try again shortly.");
        };
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value instanceof String text && !text.isBlank()) map.put(key, text);
    }
}
