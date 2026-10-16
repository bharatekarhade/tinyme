package com.tinyme.tools.handlers;

import com.tinyme.integrations.google.service.GmailClient;
import com.tinyme.integrations.google.service.GmailConfirmationRepository;
import com.tinyme.integrations.google.service.GoogleOAuthException;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GmailDraftTool implements ToolHandler {
    private final GmailClient gmail;
    private final GmailConfirmationRepository confirmations;

    @Autowired
    public GmailDraftTool(GmailClient gmail, GmailConfirmationRepository confirmations) {
        this.gmail = gmail;
        this.confirmations = confirmations;
    }

    /** Compatibility constructor for isolated handler tests. */
    GmailDraftTool(GmailClient gmail) { this.gmail = gmail; this.confirmations = null; }
    @Override public String name() { return "gmail_draft"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        try {
            String to = ToolInputs.requiredString(input, "to").trim();
            String cc = ToolInputs.optionalString(input, "cc");
            String bcc = ToolInputs.optionalString(input, "bcc");
            String subject = ToolInputs.requiredString(input, "subject").trim();
            String body = ToolInputs.requiredString(input, "body");
            String draftId = ToolInputs.optionalString(input, "draft_id");
            if (to.length() > 1000 || subject.length() > 998 || body.length() > 50_000
                    || cc != null && cc.length() > 1000 || bcc != null && bcc.length() > 1000
                    || draftId != null && draftId.length() > 200)
                throw new IllegalArgumentException("Email fields exceed the supported length");
            if (draftId != null && confirmations != null && confirmations.hasPendingDraft(draftId))
                throw new IllegalArgumentException("This draft has a pending send confirmation. Cancel it before editing the draft.");
            JsonNode draft = gmail.saveDraft(to, cc, bcc, subject, body, draftId);
            String id = GmailSearchTool.text(draft, "id");
            if (id == null || id.isBlank()) throw new IOException("Gmail did not return a draft id");
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("draft_id", id);
            output.put("to", to);
            if (cc != null && !cc.isBlank()) output.put("cc", cc);
            if (bcc != null && !bcc.isBlank()) output.put("bcc", bcc);
            output.put("subject", subject);
            return new ToolResult.Ok(output, "Draft saved: " + subject, Map.of("draft_id", id),
                    Map.of("draft_id", id, "to", to, "subject", subject));
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
}
