package com.tinyme.tools.handlers;

import com.tinyme.integrations.google.service.GmailClient;
import com.tinyme.integrations.google.service.GmailConfirmationRepository;
import com.tinyme.integrations.google.service.GmailConfirmationService;
import com.tinyme.integrations.google.service.GmailMessageParser;
import com.tinyme.integrations.google.service.GoogleMessageFormat;
import com.tinyme.integrations.google.service.GoogleOAuthException;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;

@Component
public class GmailSendTool implements ToolHandler {
    private final GmailClient gmail;
    private final GmailConfirmationService confirmations;
    private final GmailMessageParser parser = new GmailMessageParser();

    public GmailSendTool(GmailClient gmail, GmailConfirmationService confirmations) {
        this.gmail = gmail;
        this.confirmations = confirmations;
    }

    @Override public String name() { return "gmail_send"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        try {
            String draftId = ToolInputs.requiredString(input, "draft_id").trim();
            if (draftId.length() > 200) throw new IllegalArgumentException("draft_id must be 200 characters or fewer");
            JsonNode draft = gmail.getDraft(draftId);
            JsonNode messageNode = draft.get("message");
            if (messageNode == null || !messageNode.isObject())
                throw new IllegalArgumentException("That Gmail draft could not be found");
            var message = parser.parse(messageNode, true, 50_000, false);
            String to = clean(message.to());
            String subject = clean(message.subject());
            if (to.isBlank()) throw new IllegalArgumentException("The draft has no recipient");
            var confirmationId = confirmations.request(context.sessionRowId(), context.threadId(),
                    context.toolCallId(), draftId, message);
            Map<String, Object> ref = Map.of("confirmation_id", confirmationId.toString());
            return new ToolResult.Ok(Map.of("confirmation_id", confirmationId.toString(),
                    "status", "pending", "message", "Waiting for the user to confirm sending"),
                    "Email send confirmation required", ref,
                    Map.of("confirmation_id", confirmationId.toString(), "status", "pending"));
        } catch (GmailConfirmationRepository.PendingConfirmationExistsException exists) {
            return new ToolResult.Err("confirmation_pending", "Confirm or cancel the pending email before preparing another send.");
        } catch (IllegalArgumentException failure) {
            return GmailSearchTool.invalid(failure.getMessage());
        } catch (GoogleOAuthException failure) {
            return GmailSearchTool.googleError(failure.reason());
        } catch (IOException failure) {
            return GmailSearchTool.googleError(failure instanceof com.tinyme.integrations.google.service.GoogleApiHttpClient.GoogleApiException api
                    ? api.reason() : "google_unavailable");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return GmailSearchTool.googleError("google_unavailable");
        }
    }

    private static String clean(String value) { return value == null ? "" : value; }
}
