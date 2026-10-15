package com.tinyme.integrations.google.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

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
    private final GoogleApiHttpClient http;

    public CalendarClient(GoogleApiHttpClient http) { this.http = http; }

    public JsonNode listCalendars() throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        return http.get(GoogleApiHttpClient.uri(BASE, "/users/me/calendarList", Map.of()));
    }

    public JsonNode listEvents(String calendarId, Instant from, Instant to, String pageToken)
            throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        if (from == null || to == null || !from.isBefore(to))
            throw new IllegalArgumentException("Calendar range must have from before to");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("timeMin", from.toString());
        params.put("timeMax", to.toString());
        params.put("singleEvents", "true");
        params.put("orderBy", "startTime");
        if (pageToken != null && !pageToken.isBlank()) params.put("pageToken", pageToken);
        return http.get(GoogleApiHttpClient.uri(BASE,
                "/calendars/" + segment(calendarId) + "/events", params));
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
