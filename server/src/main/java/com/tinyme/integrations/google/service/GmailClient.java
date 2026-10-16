package com.tinyme.integrations.google.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.util.Properties;
import java.io.ByteArrayOutputStream;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** Thin Gmail REST adapter; message interpretation belongs to tools/agents. */
@Component
public class GmailClient {
    private static final String BASE = "https://gmail.googleapis.com/gmail/v1";
    private static final String READ_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    private static final String COMPOSE_SCOPE = "https://www.googleapis.com/auth/gmail.compose";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final java.util.List<String> SEARCH_METADATA_HEADERS =
            java.util.List.of("From", "To", "Subject", "Date", "List-Unsubscribe");
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

    public JsonNode profile() throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        return http.get(GoogleApiHttpClient.uri(BASE, "/users/me/profile", Map.of()));
    }

    public JsonNode history(String startHistoryId, String pageToken, int maxResults)
            throws IOException, InterruptedException {
        http.requireScope(READ_SCOPE);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("startHistoryId", startHistoryId);
        params.put("historyTypes", "messageAdded");
        params.put("maxResults", Integer.toString(Math.max(1, Math.min(500, maxResults))));
        if (pageToken != null && !pageToken.isBlank()) params.put("pageToken", pageToken);
        return http.get(GoogleApiHttpClient.uri(BASE, "/users/me/history", params));
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
        return saveDraft(to, cc, null, subject, body, draftId);
    }

    public JsonNode saveDraft(String to, String cc, String bcc, String subject, String body, String draftId)
            throws IOException, InterruptedException {
        http.requireScope(COMPOSE_SCOPE);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        try {
            mime.setRecipients(Message.RecipientType.TO, addresses(to));
            if (cc != null && !cc.isBlank()) mime.setRecipients(Message.RecipientType.CC, addresses(cc));
            if (bcc != null && !bcc.isBlank()) mime.setRecipients(Message.RecipientType.BCC, addresses(bcc));
            mime.setSubject(header(subject), StandardCharsets.UTF_8.name());
            mime.setText(body, StandardCharsets.UTF_8.name());
            mime.saveChanges();
        } catch (MessagingException invalid) {
            throw new IllegalArgumentException("Email address or message headers are invalid", invalid);
        }
        ByteArrayOutputStream rawBytes = new ByteArrayOutputStream();
        try { mime.writeTo(rawBytes); }
        catch (MessagingException invalid) { throw new IllegalArgumentException("Email draft could not be encoded", invalid); }
        ObjectNode rawMessage = JSON.createObjectNode();
        rawMessage.put("raw", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rawBytes.toByteArray()));
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

    private static InternetAddress[] addresses(String raw) {
        try {
            InternetAddress[] parsed = InternetAddress.parse(header(raw), true);
            if (parsed.length == 0) throw new AddressException("At least one address is required");
            for (InternetAddress address : parsed) address.validate();
            return parsed;
        } catch (AddressException invalid) {
            throw new IllegalArgumentException("Email address is invalid", invalid);
        }
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
