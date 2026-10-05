package com.tinyme.agent;



import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

final class ManagedAgentApi {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String key;
    private final URI base;

    ManagedAgentApi(String key, URI base) {
        this.key = key;
        this.base = base;
    }

    Map<?, ?> request(String method, String path, Map<?, ?> payload, boolean memory) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(60))
                .header("x-api-key", key).header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", memory ? "agent-memory-2026-07-22" : "managed-agents-2026-04-01")
                .header("Content-Type", "application/json");
        builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        var request = builder.build();
        int attempts = method.equals("GET") ? 4 : 1;
        for (int attempt = 0; ; attempt++) {
            try {
                return send(request, payload);
            } catch (IOException error) {
                boolean retryable = !(error instanceof ApiException apiError)
                        || apiError.status == 429 || (apiError.status >= 500 && apiError.status <= 599);
                if (!retryable || attempt + 1 >= attempts) throw error;
                Thread.sleep(250L << attempt);
            }
        }
    }

    private Map<?, ?> send(HttpRequest request, Map<?, ?> payload) throws IOException, InterruptedException {
        // POST requests are sent once: a lost response may follow successful creation.
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String type = "unknown";
            String detail = "No API error message provided";
            try {
                var body = json.readValue(response.body(), Map.class);
                if (body.get("error") instanceof Map<?, ?> error && error.get("type") instanceof String name) {
                    type = name;
                    if (error.get("message") instanceof String message) detail = message;
                }
            } catch (RuntimeException ignored) {
                // Never include response bodies, memory contents or credentials in errors.
            }
            String requestId = response.headers().firstValue("request-id").orElse("unavailable");
            String context = request.method() + " " + request.uri().getPath() + ": " + detail
                    + " [request-id=" + requestId + "]";
            throw new ApiException(response.statusCode(), type, sanitize(context, payload));
        }
        return json.readValue(response.body(), Map.class);
    }

    private String sanitize(String message, Map<?, ?> payload) {
        String safe = key.isEmpty() ? message : message.replace(key, "[redacted]");
        if (payload != null) {
            // API validation messages may echo submitted memory or system content.
            for (String field : java.util.List.of("content", "system")) {
                if (payload.get(field) instanceof String text && !text.isEmpty()) {
                    safe = safe.replace(text, "[redacted content]");
                }
            }
        }
        safe = safe.replaceAll("sk-ant-[A-Za-z0-9_-]+", "[redacted]")
                .replaceAll("[\\p{Cntrl}]", " ");
        return safe.length() <= 1500 ? safe : safe.substring(0, 1500) + "…";
    }

    static final class ApiException extends IOException {
        final int status;
        final String type;

        ApiException(int status, String type) {
            this(status, type, "");
        }

        ApiException(int status, String type, String detail) {
            super("Managed Agents API returned HTTP " + status + " (" + type + ")"
                    + (detail.isEmpty() ? "" : ": " + detail));
            this.status = status;
            this.type = type;
        }
    }
}
