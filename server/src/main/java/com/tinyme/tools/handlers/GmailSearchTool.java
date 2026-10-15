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
public class GmailSearchTool implements ToolHandler {
    private final GmailClient gmail;
    private final GmailMessageParser parser = new GmailMessageParser();

    public GmailSearchTool(GmailClient gmail) { this.gmail = gmail; }

    @Override public String name() { return "gmail_search"; }

    @Override
    public ToolResult handle(JsonNode input, ToolContext context) {
        if (input == null || !input.isObject()) return invalid("Input must be an object");
        try {
            String query = ToolInputs.requiredString(input, "query").trim();
            if (query.length() > 500) throw new IllegalArgumentException("query must be 500 characters or fewer");
            Integer requested = ToolInputs.optionalInt(input, "max_results");
            int limit = requested == null ? 10 : requested;
            if (limit < 1 || limit > 20) throw new IllegalArgumentException("max_results must be between 1 and 20");

            JsonNode response = gmail.searchMessages(query, limit, null);
            JsonNode listed = response.get("messages");
            List<Map<String, Object>> results = new ArrayList<>();
            if (listed != null && listed.isArray()) {
                for (JsonNode row : listed) {
                    if (results.size() >= limit) break;
                    String id = text(row, "id");
                    if (id == null || id.isBlank()) continue;
                    JsonNode metadata = gmail.getMessage(id, GoogleMessageFormat.METADATA);
                    var message = parser.parse(metadata, false, 0);
                    Map<String, Object> item = new LinkedHashMap<>();
                    put(item, "message_id", message.id());
                    put(item, "thread_id", message.threadId());
                    put(item, "from", message.from());
                    put(item, "to", message.to());
                    put(item, "subject", message.subject());
                    put(item, "date", message.date());
                    put(item, "snippet", message.snippet());
                    results.add(item);
                }
            }
            String summary = results.size() + (results.size() == 1 ? " email found" : " emails found");
            List<String> ids = results.stream().map(item -> (String) item.get("message_id")).toList();
            return new ToolResult.Ok(Map.of("query", query, "messages", results), summary, Map.of(),
                    Map.of("message_ids", ids, "count", ids.size()));
        } catch (IllegalArgumentException failure) {
            return invalid(failure.getMessage());
        } catch (GoogleOAuthException failure) {
            return googleError(failure.reason());
        } catch (IOException failure) {
            return googleError(failure instanceof com.tinyme.integrations.google.service.GoogleApiHttpClient.GoogleApiException api
                    ? api.reason() : "google_unavailable");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return googleError("google_unavailable");
        }
    }

    static ToolResult.Err googleError(String reason) {
        return switch (reason == null ? "" : reason) {
            case "google_not_connected" -> new ToolResult.Err("google_not_connected", "Connect Google to search Gmail.");
            case "google_reauth_required" -> new ToolResult.Err("google_reauth_required", "Reconnect your Google account to use Gmail.");
            case "scope_missing" -> new ToolResult.Err("scope_missing", "Gmail access is not allowed. Reconnect Google and allow Gmail.");
            default -> new ToolResult.Err("google_unavailable", "Gmail is unavailable right now. Try again shortly.");
        };
    }

    static ToolResult.Err invalid(String message) {
        return new ToolResult.Err("validation_error", message == null ? "Invalid input" : message);
    }

    static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() || !value.isString() ? null : value.asString();
    }

    static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }
}
