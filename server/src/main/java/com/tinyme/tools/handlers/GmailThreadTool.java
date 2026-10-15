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
public class GmailThreadTool implements ToolHandler {
    private final GmailClient gmail;
    private final GmailMessageParser parser = new GmailMessageParser();

    public GmailThreadTool(GmailClient gmail) { this.gmail = gmail; }
    @Override public String name() { return "gmail_thread"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return GmailSearchTool.invalid("Input must be an object");
        try {
            String id = ToolInputs.requiredString(input, "thread_id").trim();
            if (id.length() > 200) throw new IllegalArgumentException("thread_id must be 200 characters or fewer");
            JsonNode response = gmail.getThread(id, GoogleMessageFormat.FULL);
            JsonNode messages = response.get("messages");
            List<Map<String, Object>> output = new ArrayList<>();
            int remaining = GmailMessageParser.MAX_THREAD_CHARS;
            if (messages != null && messages.isArray()) {
                for (JsonNode row : messages) {
                    if (output.size() >= 20 || remaining <= 0) break;
                    var message = parser.parse(row, true, Math.min(2_000, remaining));
                    Map<String, Object> item = new LinkedHashMap<>();
                    GmailSearchTool.put(item, "message_id", message.id());
                    GmailSearchTool.put(item, "from", message.from());
                    GmailSearchTool.put(item, "to", message.to());
                    GmailSearchTool.put(item, "subject", message.subject());
                    GmailSearchTool.put(item, "date", message.date());
                    String body = message.body() == null ? "" : message.body();
                    item.put("body", body);
                    output.add(item);
                    remaining -= body.length();
                }
            }
            String summary = output.size() + (output.size() == 1 ? " message in thread" : " messages in thread");
            List<String> messageIds = output.stream().map(item -> (String) item.get("message_id"))
                    .filter(java.util.Objects::nonNull).toList();
            return new ToolResult.Ok(Map.of("thread_id", id, "messages", output), summary, Map.of(),
                    Map.of("thread_id", id, "message_ids", messageIds, "count", output.size()));
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
