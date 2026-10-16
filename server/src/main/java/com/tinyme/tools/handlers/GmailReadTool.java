package com.tinyme.tools.handlers;

import com.tinyme.integrations.google.service.GmailClient;
import com.tinyme.integrations.google.service.GmailMessageParser;
import com.tinyme.integrations.google.service.GoogleMessageFormat;
import com.tinyme.integrations.google.service.GoogleOAuthException;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolHandler;
import com.tinyme.tools.model.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GmailReadTool implements ToolHandler {
    private final GmailClient gmail;
    private final GmailMessageParser parser = new GmailMessageParser();

    public GmailReadTool(GmailClient gmail) { this.gmail = gmail; }
    @Override public String name() { return "gmail_read"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return GmailSearchTool.invalid("Input must be an object");
        try {
            String id = ToolInputs.requiredString(input, "message_id").trim();
            if (id.length() > 200) throw new IllegalArgumentException("message_id must be 200 characters or fewer");
            var message = parser.parse(gmail.getMessage(id, GoogleMessageFormat.FULL), true,
                    GmailMessageParser.MAX_MESSAGE_CHARS);
            Map<String, Object> output = new LinkedHashMap<>();
            GmailSearchTool.put(output, "message_id", message.id());
            GmailSearchTool.put(output, "thread_id", message.threadId());
            GmailSearchTool.put(output, "from", message.from());
            GmailSearchTool.put(output, "to", message.to());
            GmailSearchTool.put(output, "subject", message.subject());
            GmailSearchTool.put(output, "date", message.date());
            output.put("body", message.body());
            output.put("attachments", attachments(message.attachments()));
            String summary = "Read email: " + safeSubject(message.subject());
            Map<String, Object> stored = new LinkedHashMap<>();
            GmailSearchTool.put(stored, "message_id", message.id());
            GmailSearchTool.put(stored, "thread_id", message.threadId());
            stored.put("attachment_count", message.attachments().size());
            return new ToolResult.Ok(output, summary, Map.of(), stored);
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

    static List<Map<String, Object>> attachments(List<GmailMessageParser.Attachment> attachments) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var attachment : attachments) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filename", attachment.filename());
            GmailSearchTool.put(item, "mime_type", attachment.mimeType());
            GmailSearchTool.put(item, "attachment_id", attachment.attachmentId());
            result.add(item);
        }
        return result;
    }

    private static String safeSubject(String subject) {
        return subject == null || subject.isBlank() ? "message" : subject.substring(0, Math.min(120, subject.length()));
    }
}
