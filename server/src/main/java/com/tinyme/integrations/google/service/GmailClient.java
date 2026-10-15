package com.tinyme.integrations.google.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thin Gmail REST adapter; message interpretation belongs to tools/agents. */
@Component
public class GmailClient {
    private static final String BASE = "https://gmail.googleapis.com/gmail/v1";
    private static final String READ_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    private static final java.util.List<String> SEARCH_METADATA_HEADERS = java.util.List.of("From", "To", "Subject", "Date");
    private final GoogleApiHttpClient http;

    public GmailClient(GoogleApiHttpClient http) { this.http = http; }

    public JsonNode searchMessages(String query, int maxResults, String pageToken)
            throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", query);
        params.put("maxResults", Integer.toString(Math.max(1, Math.min(100, maxResults))));
        if (pageToken != null && !pageToken.isBlank()) params.put("pageToken", pageToken);
        return http.get(GoogleApiHttpClient.uri(BASE, "/users/me/messages", params));
    }

    public JsonNode getMessage(String id, GoogleMessageFormat format) throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        return http.get(messageUri("/users/me/messages/" + segment(id), format));
    }

    public JsonNode getThread(String id, GoogleMessageFormat format) throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        return http.get(messageUri("/users/me/threads/" + segment(id), format));
    }

    private static java.net.URI messageUri(String path, GoogleMessageFormat format) {
        Map<String, String> params = Map.of("format", format.apiValue());
        Map<String, java.util.List<String>> headers = format == GoogleMessageFormat.METADATA
                ? Map.of("metadataHeaders", SEARCH_METADATA_HEADERS) : Map.of();
        return GoogleApiHttpClient.uri(BASE, path, params, headers);
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
