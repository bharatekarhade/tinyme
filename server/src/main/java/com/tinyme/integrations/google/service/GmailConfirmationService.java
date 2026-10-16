package com.tinyme.integrations.google.service;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class GmailConfirmationService {
    private static final Duration LIFETIME = Duration.ofMinutes(10);
    private final GmailConfirmationRepository confirmations;
    private final GmailClient gmail;
    private final CalendarClient calendar;
    private final GoogleAccountRepository accounts;

    public GmailConfirmationService(GmailConfirmationRepository confirmations, GmailClient gmail,
                                    CalendarClient calendar, GoogleAccountRepository accounts) {
        this.confirmations = confirmations;
        this.gmail = gmail;
        this.calendar = calendar;
        this.accounts = accounts;
    }

    public UUID request(UUID sessionId, String threadId, UUID toolCallId, String draftId,
                        GmailMessageParser.Message message) {
        List<String> names = message.attachments().stream().map(GmailMessageParser.Attachment::filename).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "email");
        payload.put("draft_id", draftId);
        payload.put("to", value(message.to()));
        payload.put("cc", value(message.cc()));
        payload.put("bcc", value(message.bcc()));
        payload.put("subject", value(message.subject()));
        payload.put("body", value(message.body()));
        payload.put("preview", value(message.body()).substring(0, Math.min(300, value(message.body()).length())));
        payload.put("attachments", names);
        payload.put("draft_hash", hash(message.to(), message.cc(), message.bcc(), message.subject(), message.body(), names));
        return confirmations.create(sessionId, threadId, toolCallId, "gmail_send", payload, LIFETIME);
    }

    public UUID requestCalendar(UUID sessionId, String threadId, UUID toolCallId, String calendarId,
                                String eventId, String response, String title, String startsAt, String endsAt) {
        return confirmations.create(sessionId, threadId, toolCallId, "calendar_respond",
                Map.of("kind", "calendar_invite", "calendar_id", calendarId, "event_id", eventId,
                        "response", response, "title", title == null ? "Calendar invitation" : title,
                        "starts_at", startsAt == null ? "" : startsAt,
                        "ends_at", endsAt == null ? "" : endsAt), LIFETIME);
    }

    public List<Pending> pending() {
        return confirmations.pending().stream().map(row -> new Pending(row.id().toString(),
                text(row.payload(), "kind"), text(row.payload(), "to"),
                text(row.payload(), "cc"), text(row.payload(), "bcc"),
                text(row.payload(), "subject"), text(row.payload(), "preview"), text(row.payload(), "body"),
                stringList(row.payload().path("attachments")),
                text(row.payload(), "title"), text(row.payload(), "response"),
                text(row.payload(), "starts_at"), text(row.payload(), "ends_at"), row.expiresAt())).toList();
    }

    public Decision decide(UUID id, String decision) throws IOException, InterruptedException {
        if (!"send".equals(decision) && !"cancel".equals(decision))
            throw new IllegalArgumentException("decision must be send or cancel");
        if ("cancel".equals(decision)) {
            var changed = confirmations.decide(id, false);
            if (changed.isEmpty()) {
                confirmations.expire(id);
                throw new MissingConfirmationException();
            }
            return new Decision(id, "rejected", null);
        }

        JsonNode pendingPayload = confirmations.pendingPayload(id).orElse(null);
        if (pendingPayload == null) {
            confirmations.expire(id);
            throw new MissingConfirmationException();
        }
        String draftId = text(pendingPayload, "draft_id");
        if ("email".equals(text(pendingPayload, "kind"))) try {
            JsonNode draft;
            try {
                draft = gmail.getDraft(draftId);
            } catch (IOException unavailable) {
                if (unavailable instanceof GoogleApiHttpClient.GoogleApiException api
                        && (api.statusCode() == 404 || api.statusCode() == 410)) {
                    confirmations.expirePending(id);
                    throw new ConfirmationChangedException();
                }
                throw unavailable;
            }
            JsonNode messageNode = draft.get("message");
            GmailMessageParser.Message current = messageNode == null ? null
                    : new GmailMessageParser().parse(messageNode, true, 50_000, false);
            List<String> names = current == null ? List.of()
                    : current.attachments().stream().map(GmailMessageParser.Attachment::filename).toList();
            String currentHash = current == null ? "" : hash(current.to(), current.cc(), current.bcc(),
                    current.subject(), current.body(), names);
            if (!currentHash.equals(text(pendingPayload, "draft_hash"))) {
                confirmations.expirePending(id);
                throw new ConfirmationChangedException();
            }
        } catch (ConfirmationChangedException changed) {
            throw changed;
        } catch (IOException unavailable) {
            throw unavailable;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (RuntimeException missingOrUnavailable) {
            confirmations.expirePending(id);
            throw new ConfirmationChangedException();
        }

        JsonNode payload = confirmations.decide(id, true).orElse(null);
        if (payload == null) {
            confirmations.expire(id);
            throw new MissingConfirmationException();
        }
        draftId = text(payload, "draft_id");
        try {
            if ("calendar_invite".equals(text(payload, "kind"))) {
                String email = accounts.find().filter(account -> "connected".equals(account.status()))
                        .map(GoogleAccountRepository.GoogleAccount::email)
                        .orElseThrow(() -> new GoogleOAuthException("google_not_connected"));
                calendar.respondToInvite(text(payload, "calendar_id"), text(payload, "event_id"), email,
                        text(payload, "response"));
                confirmations.markCompleted(id, "responded");
                return new Decision(id, "responded", null);
            }
            JsonNode result = gmail.sendDraft(draftId);
            confirmations.markCompleted(id, "sent");
            return new Decision(id, "sent", text(result, "id"));
        } catch (IOException | InterruptedException | RuntimeException failure) {
            confirmations.markFailed(id);
            throw failure;
        }
    }

    public List<String> consumeRecentContext(UUID sessionId, Instant now) {
        return confirmations.consumeRecentDecisions(sessionId).stream().map(decision -> {
            JsonNode payload = decision.payload();
            String subject = text(payload, "subject");
            if (subject.isBlank()) subject = text(payload, "title");
            String recipient = text(payload, "to");
            String action = switch (decision.status()) {
                case "sent" -> "sent";
                case "responded" -> "replied";
                case "rejected" -> "cancelled";
                default -> "could not be completed";
            };
            long minutes = Math.max(0, Duration.between(decision.decidedAt(), now).toMinutes());
            String ago = minutes < 1 ? "just now" : minutes + " min ago";
            String target = recipient.isBlank() ? "" : " to " + recipient;
            return "recent: email \"" + subject + "\"" + target + " " + action + " " + ago;
        }).toList();
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() || !value.isString() ? "" : value.stringValue();
    }

    private static String value(String text) { return text == null ? "" : text; }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) if (value.isString()) result.add(value.stringValue());
        return List.copyOf(result);
    }

    static String hash(String to, String cc, String bcc, String subject, String body, List<String> attachments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : List.of(value(to), value(cc), value(bcc), value(subject), value(body))) {
                digest.update(field.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            for (String attachment : attachments) {
                digest.update(value(attachment).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record Pending(String id, String kind, String to, String cc, String bcc,
                          String subject, String preview, String body, List<String> attachments,
                          String title, String response,
                          @com.fasterxml.jackson.annotation.JsonProperty("starts_at") String startsAt,
                          @com.fasterxml.jackson.annotation.JsonProperty("ends_at") String endsAt,
                          @com.fasterxml.jackson.annotation.JsonProperty("expires_at") Instant expiresAt) {}
    public record Decision(UUID id, String status,
                           @com.fasterxml.jackson.annotation.JsonProperty("message_id") String messageId) {}

    public static final class MissingConfirmationException extends RuntimeException {}
    public static final class ConfirmationChangedException extends RuntimeException {}
}
