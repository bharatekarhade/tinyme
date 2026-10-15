package com.tinyme.integrations.google.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** Thin Gmail REST adapter; message interpretation belongs to tools/agents. */
@Component
public class GmailClient {
    private static final String BASE = "https://gmail.googleapis.com/gmail/v1";
    private static final String READ_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    private static final String COMPOSE_SCOPE = "https://www.googleapis.com/auth/gmail.compose";
    private static final JsonMapper JSON = JsonMapper.builder().build();
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

    public JsonNode getDraft(String id) throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        return http.get(GoogleApiHttpClient.uri(BASE, "/users/me/drafts/" + segment(id), Map.of("format", "full")));
    }

    public JsonNode saveDraft(String to, String cc, String subject, String body, String draftId)
            throws IOException, InterruptedException {
        http.requireScope(COMPOSE_SCOPE);
        StringBuilder mime = new StringBuilder();
        mime.append("To: ").append(header(to)).append("\r\n");
        if (cc != null && !cc.isBlank()) mime.append("Cc: ").append(header(cc)).append("\r\n");
        mime.append("Subject: ").append(header(subject)).append("\r\n")
                .append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n")
                .append("Content-Transfer-Encoding: 8bit\r\n\r\n").append(body);
        ObjectNode rawMessage = JSON.createObjectNode();
        rawMessage.put("raw", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mime.toString().getBytes(StandardCharsets.UTF_8)));
        ObjectNode request = JSON.createObjectNode();
        request.set("message", rawMessage);
        if (draftId == null) {
            return http.sendJson("POST", GoogleApiHttpClient.uri(BASE, "/users/me/drafts", Map.of()), request);
        }
        return http.sendJson("PUT", GoogleApiHttpClient.uri(BASE, "/users/me/drafts/" + segment(draftId), Map.of()), request);
    }

    public JsonNode sendDraft(String id) throws IOException, InterruptedException {
        http.requireScope(COMPOSE_SCOPE);
        ObjectNode request = JSON.createObjectNode();
        request.put("id", id);
        return http.sendJson("POST", GoogleApiHttpClient.uri(BASE, "/users/me/drafts/send", Map.of()), request);
    }

    private static String header(String value) {
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Email headers must be a single line");
        return value;
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
