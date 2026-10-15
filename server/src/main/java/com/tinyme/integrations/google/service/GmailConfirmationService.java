package com.tinyme.integrations.google.service;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GmailConfirmationService {
    private static final Duration LIFETIME = Duration.ofMinutes(10);
    private final GmailConfirmationRepository confirmations;
    private final GmailClient gmail;

    public GmailConfirmationService(GmailConfirmationRepository confirmations, GmailClient gmail) {
        this.confirmations = confirmations;
        this.gmail = gmail;
    }

    public UUID request(UUID sessionId, String threadId, UUID toolCallId, String draftId,
                        String to, String subject, String preview) {
        return confirmations.create(sessionId, threadId, toolCallId,
                Map.of("draft_id", draftId, "to", to, "subject", subject, "preview", preview), LIFETIME);
    }

    public List<Pending> pending() {
        return confirmations.pending().stream().map(row -> new Pending(row.id().toString(),
                text(row.payload(), "to"), text(row.payload(), "subject"), text(row.payload(), "preview"),
                row.expiresAt())).toList();
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

        JsonNode payload = confirmations.decide(id, true).orElse(null);
        if (payload == null) {
            confirmations.expire(id);
            throw new MissingConfirmationException();
        }
        String draftId = text(payload, "draft_id");
        try {
            JsonNode result = gmail.sendDraft(draftId);
            return new Decision(id, "sent", text(result, "id"));
        } catch (IOException | InterruptedException | RuntimeException failure) {
            confirmations.markFailed(id);
            throw failure;
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() || !value.isString() ? "" : value.stringValue();
    }

    public record Pending(String id, String to, String subject, String preview,
                          @com.fasterxml.jackson.annotation.JsonProperty("expires_at") Instant expiresAt) {}
    public record Decision(UUID id, String status,
                           @com.fasterxml.jackson.annotation.JsonProperty("message_id") String messageId) {}

    public static final class MissingConfirmationException extends RuntimeException {}
}
