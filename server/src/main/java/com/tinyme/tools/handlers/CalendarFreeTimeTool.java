package com.tinyme.tools.handlers;

import com.tinyme.integrations.google.service.CalendarClient;
import com.tinyme.integrations.google.service.GoogleApiHttpClient;
import com.tinyme.integrations.google.service.GoogleOAuthException;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class CalendarFreeTimeTool implements ToolHandler {
    private final CalendarClient calendar;

    public CalendarFreeTimeTool(CalendarClient calendar) { this.calendar = calendar; }
    @Override public String name() { return "calendar_free_time"; }

    @Override public ToolResult handle(JsonNode input, ToolContext context) {
        try {
            Instant from = CalendarEventsTool.parseInstant(ToolInputs.requiredString(input, "from"));
            Instant to = CalendarEventsTool.parseInstant(ToolInputs.requiredString(input, "to"));
            Integer durationValue = ToolInputs.optionalInt(input, "duration_minutes");
            int durationMinutes = durationValue == null ? 30 : durationValue;
            if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to");
            if (durationMinutes < 5 || durationMinutes > 720)
                throw new IllegalArgumentException("duration_minutes must be between 5 and 720");
            List<String> calendarIds = new ArrayList<>();
            String page = null;
            do {
                JsonNode responsePage = calendar.listCalendars(page);
                JsonNode calendars = responsePage.path("items");
                if (calendars.isArray()) for (JsonNode row : calendars) {
                    String id = CalendarEventsTool.text(row, "id");
                    if (id != null && "owner".equals(CalendarEventsTool.text(row, "accessRole"))
                            && row.path("selected").asBoolean(false)) calendarIds.add(id);
                }
                page = CalendarEventsTool.text(responsePage, "nextPageToken");
            } while (page != null);
            String zone = ToolInputs.optionalString(input, "time_zone");
            JsonNode response = calendar.freeBusy(from, to, calendarIds,
                    zone == null || zone.isBlank() ? context.zone().getId() : zone);
            List<Interval> busy = new ArrayList<>();
            JsonNode calendarsBusy = response.path("calendars");
            for (String id : calendarIds) {
                JsonNode periods = calendarsBusy.path(id).path("busy");
                if (!periods.isArray()) continue;
                for (JsonNode period : periods) {
                    Instant start = CalendarEventsTool.parseInstant(CalendarEventsTool.text(period, "start"));
                    Instant end = CalendarEventsTool.parseInstant(CalendarEventsTool.text(period, "end"));
                    if (start.isBefore(end)) busy.add(new Interval(start, end));
                }
            }
            busy.sort(Comparator.comparing(Interval::start));
            List<Map<String, String>> slots = new ArrayList<>();
            Instant cursor = from;
            Duration requested = Duration.ofMinutes(durationMinutes);
            for (Interval interval : merge(busy)) {
                addSlot(slots, cursor, interval.start(), requested);
                if (interval.end().isAfter(cursor)) cursor = interval.end();
            }
            addSlot(slots, cursor, to, requested);
            return new ToolResult.Ok(Map.of("duration_minutes", durationMinutes, "slots", slots),
                    slots.isEmpty() ? "No free slots found" : slots.size() + " free slots found", Map.of(),
                    Map.of("count", slots.size()));
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

    private static List<Interval> merge(List<Interval> intervals) {
        List<Interval> merged = new ArrayList<>();
        for (Interval next : intervals) {
            if (merged.isEmpty() || next.start().isAfter(merged.getLast().end())) merged.add(next);
            else {
                Interval previous = merged.removeLast();
                merged.add(new Interval(previous.start(), previous.end().isAfter(next.end()) ? previous.end() : next.end()));
            }
        }
        return merged;
    }

    private static void addSlot(List<Map<String, String>> slots, Instant start, Instant end, Duration duration) {
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(duration) < 0 || slots.size() >= 20) return;
        slots.add(Map.of("start", start.toString(), "end", end.toString()));
    }

    private record Interval(Instant start, Instant end) {}
}
