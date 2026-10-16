package com.tinyme.integrations.google.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thin Google Calendar REST adapter; plan and event policies live above this client. */
@Component
public class CalendarClient {
    private static final String BASE = "https://www.googleapis.com/calendar/v3";
    private static final String READ_SCOPE = "https://www.googleapis.com/auth/calendar.events";
    private static final String CALENDAR_LIST_SCOPE = "https://www.googleapis.com/auth/calendar.calendarlist.readonly";
    private static final String FREEBUSY_SCOPE = "https://www.googleapis.com/auth/calendar.events.freebusy";
    private static final String CREATED_CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.app.created";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final GoogleApiHttpClient http;

    public CalendarClient(GoogleApiHttpClient http) { this.http = http; }

    public JsonNode listCalendars() throws IOException, InterruptedException {
        return listCalendars(null);
    }

    public JsonNode listCalendars(String pageToken) throws IOException, InterruptedException {
        http.requireScope(CALENDAR_LIST_SCOPE);
        Map<String, String> params = pageToken == null || pageToken.isBlank()
                ? Map.of() : Map.of("pageToken", pageToken);
        return http.get(GoogleApiHttpClient.uri(BASE, "/users/me/calendarList", params));
    }

    public JsonNode listEvents(String calendarId, Instant from, Instant to, String pageToken)
            throws IOException, InterruptedException {
        return listEvents(calendarId, from, to, null, pageToken);
    }

    public JsonNode listEvents(String calendarId, Instant from, Instant to, String syncToken, String pageToken)
            throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        if (syncToken == null && (from == null || to == null || !from.isBefore(to)))
            throw new IllegalArgumentException("Calendar range must have from before to");
        Map<String, String> params = new LinkedHashMap<>();
        if (syncToken == null) {
            params.put("timeMin", from.toString());
            params.put("timeMax", to.toString());
        }
        params.put("singleEvents", "true");
        params.put("showDeleted", "true");
        if (syncToken != null && !syncToken.isBlank()) params.put("syncToken", syncToken);
        else params.put("orderBy", "startTime");
        if (pageToken != null && !pageToken.isBlank()) params.put("pageToken", pageToken);
        return http.get(GoogleApiHttpClient.uri(BASE,
                "/calendars/" + segment(calendarId) + "/events", params));
    }

    /** Full and incremental sync requests use the identical unrestricted filter set required by sync tokens. */
    public JsonNode listSyncEvents(String calendarId, String syncToken, String pageToken)
            throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("singleEvents", "false");
        params.put("showDeleted", "true");
        if (syncToken != null && !syncToken.isBlank()) params.put("syncToken", syncToken);
        if (pageToken != null && !pageToken.isBlank()) params.put("pageToken", pageToken);
        return http.get(GoogleApiHttpClient.uri(BASE,
                "/calendars/" + segment(calendarId) + "/events", params));
    }

    /** Finds a possibly-created TinyMe mirror after a lost create response. */
    public JsonNode listByPrivateProperty(String calendarId, String property, String value, String pageToken)
            throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("privateExtendedProperty", property + "=" + value);
        params.put("showDeleted", "false");
        params.put("singleEvents", "false");
        if (pageToken != null && !pageToken.isBlank()) params.put("pageToken", pageToken);
        return http.get(GoogleApiHttpClient.uri(BASE,
                "/calendars/" + segment(calendarId) + "/events", params));
    }

    public JsonNode listEventInstances(String calendarId, String eventId, Instant from, Instant to,
                                       String pageToken) throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("timeMin", from.toString());
        params.put("timeMax", to.toString());
        params.put("showDeleted", "true");
        if (pageToken != null && !pageToken.isBlank()) params.put("pageToken", pageToken);
        return http.get(GoogleApiHttpClient.uri(BASE,
                "/calendars/" + segment(calendarId) + "/events/" + segment(eventId) + "/instances", params));
    }

    public JsonNode createTinyMeCalendar(String timeZone) throws IOException, InterruptedException {
        http.requireScope(CREATED_CALENDAR_SCOPE);
        ObjectNode body = JSON.createObjectNode().put("summary", "TinyMe");
        if (timeZone != null && !timeZone.isBlank()) body.put("timeZone", timeZone);
        return http.sendJson("POST", GoogleApiHttpClient.uri(BASE, "/calendars", Map.of()), body);
    }

    public JsonNode createEvent(String calendarId, JsonNode event) throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        return http.sendJson("POST", GoogleApiHttpClient.uri(BASE,
                "/calendars/" + segment(calendarId) + "/events", Map.of("sendUpdates", "none")), event);
    }

    public JsonNode updateEvent(String calendarId, String eventId, String etag, JsonNode event)
            throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        Map<String, String> headers = etag == null || etag.isBlank() ? Map.of() : Map.of("If-Match", etag);
        return http.sendJson("PATCH", GoogleApiHttpClient.uri(BASE,
                "/calendars/" + segment(calendarId) + "/events/" + segment(eventId),
                Map.of("sendUpdates", "none")), event, headers);
    }

    public void deleteEvent(String calendarId, String eventId, String etag) throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        http.delete(GoogleApiHttpClient.uri(BASE,
                "/calendars/" + segment(calendarId) + "/events/" + segment(eventId),
                Map.of("sendUpdates", "none")), etag);
    }

    public JsonNode freeBusy(Instant from, Instant to, java.util.List<String> calendarIds, String timeZone)
            throws IOException, InterruptedException {
        http.requireScope(FREEBUSY_SCOPE);
        if (from == null || to == null || !from.isBefore(to))
            throw new IllegalArgumentException("Calendar range must have from before to");
        ObjectNode request = JSON.createObjectNode();
        request.put("timeMin", from.toString());
        request.put("timeMax", to.toString());
        if (timeZone != null && !timeZone.isBlank()) request.put("timeZone", timeZone);
        ArrayNode items = request.putArray("items");
        calendarIds.stream().filter(id -> id != null && !id.isBlank()).distinct()
                .forEach(id -> items.addObject().put("id", id));
        return http.sendJson("POST", GoogleApiHttpClient.uri(BASE, "/freeBusy", Map.of()), request);
    }

    public JsonNode getEvent(String calendarId, String eventId) throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        return http.get(GoogleApiHttpClient.uri(BASE,
                "/calendars/" + segment(calendarId) + "/events/" + segment(eventId), Map.of()));
    }

    public JsonNode respondToInvite(String calendarId, String eventId, String attendeeEmail, String response)
            throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        JsonNode event = getEvent(calendarId, eventId);
        JsonNode attendees = event == null ? null : event.get("attendees");
        if (attendees == null || !attendees.isArray())
            throw new IllegalArgumentException("This event has no response-capable attendee");
        ObjectNode patch = JSON.createObjectNode();
        ArrayNode updatedAttendees = patch.putArray("attendees");
        boolean matched = false;
        for (JsonNode attendee : attendees) {
            if (!attendee.isObject()) continue;
            ObjectNode copy = ((ObjectNode) attendee).deepCopy();
            boolean self = copy.path("self").asBoolean(false)
                    || attendeeEmail != null && attendeeEmail.equalsIgnoreCase(copy.path("email").asString(""));
            if (self) {
                copy.put("responseStatus", response);
                matched = true;
            }
            updatedAttendees.add(copy);
        }
        if (!matched) throw new IllegalArgumentException("This invite is not addressed to the connected Google account");
        return http.sendJson("PATCH", GoogleApiHttpClient.uri(BASE,
                "/calendars/" + segment(calendarId) + "/events/" + segment(eventId),
                Map.of("sendUpdates", "all")), patch);
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
