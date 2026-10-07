package com.tinyme.agent.client;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ManagedAgentApi {
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String key;
    private final URI base;

    public ManagedAgentApi(String key, URI base) {
        this(key, base, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    ManagedAgentApi(String key, URI base, HttpClient client) {
        this.key = key;
        this.base = base;
        this.client = client;
    }

    public String createSession(Map<?, ?> body) throws IOException, InterruptedException {
        Map<?, ?> response = request("POST", "/v1/sessions", Objects.requireNonNull(body, "body"), false);
        if (!(response.get("id") instanceof String id) || !id.matches("sesn_[A-Za-z0-9_-]+")) {
            throw new IOException("Managed Agents session response is missing a valid session ID");
        }
        return id;
    }

    public void sendEvents(String sessionId, List<? extends Map<String, ?>> events) throws IOException, InterruptedException {
        // request() sends POSTs exactly once, even on 429, 5xx, or a lost response.
        request("POST", sessionPath(sessionId) + "/events",
                Map.of("events", Objects.requireNonNull(events, "events")), false);
    }

    public EventStream openStream(String sessionId) throws IOException, InterruptedException {
        // Deliberately no HttpRequest timeout: the stream lives for the entire turn.
        var request = requestBuilder(sessionPath(sessionId) + "/events/stream", false)
                .header("Accept", "text/event-stream").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        InputStream body = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (body) {
                throw apiError(response.statusCode(), response.headers(), request,
                        new String(body.readAllBytes(), StandardCharsets.UTF_8), null);
            }
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.split(";", 2)[0].trim().equalsIgnoreCase("text/event-stream")) {
            body.close();
            throw new IOException("Managed Agents stream response must have Content-Type text/event-stream");
        }
        // No automatic reconnect: the caller owns turn state and event replay handling.
        return new EventStream(body);
    }

    private static String sessionPath(String sessionId) {
        if (sessionId == null || !sessionId.matches("sesn_[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid Managed Agents session ID");
        }
        return "/v1/sessions/" + sessionId;
    }

    private HttpRequest.Builder requestBuilder(String path, boolean memory) {
        return HttpRequest.newBuilder(base.resolve(path))
                .header("x-api-key", key).header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", memory ? "agent-memory-2026-07-22" : "managed-agents-2026-04-01")
                .header("Content-Type", "application/json");
    }

    public Map<?, ?> request(String method, String path, Map<?, ?> payload, boolean memory) throws IOException, InterruptedException {
        var builder = requestBuilder(path, memory).timeout(Duration.ofSeconds(60));
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
            throw apiError(response.statusCode(), response.headers(), request, response.body(), payload);
        }
        if (response.body().isBlank()) return Map.of();
        return json.readValue(response.body(), Map.class);
    }

    private ApiException apiError(int status, HttpHeaders headers, HttpRequest request,
                                  String responseBody, Map<?, ?> payload) {
        String type = "unknown";
        String detail = "No API error message provided";
        try {
            var body = json.readValue(responseBody, Map.class);
            if (body.get("error") instanceof Map<?, ?> error && error.get("type") instanceof String name) {
                type = name;
                if (error.get("message") instanceof String message) detail = message;
            }
        } catch (RuntimeException ignored) {
            // Never include raw response bodies or credentials in errors.
        }
        String requestId = headers.firstValue("request-id").orElse("unavailable");
        String context = request.method() + " " + request.uri().getPath() + ": " + detail
                + " [request-id=" + requestId + "]";
        return new ApiException(status, type, sanitize(context, payload));
    }

    private String sanitize(String message, Map<?, ?> payload) {
        String safe = key.isEmpty() ? message : message.replace(key, "[redacted]");
        safe = redactContent(safe, payload);
        safe = safe.replaceAll("sk-ant-[A-Za-z0-9_-]+", "[redacted]")
                .replaceAll("[\\p{Cntrl}]", " ");
        return safe.length() <= 1500 ? safe : safe.substring(0, 1500) + "…";
    }

    private String redactContent(String message, Object payload) {
        if (payload instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                Object value = entry.getValue();
                if (List.of("content", "system", "text").contains(entry.getKey())
                        && value instanceof String text && !text.isEmpty()) {
                    message = message.replace(text, "[redacted content]");
                }
                message = redactContent(message, value);
            }
        } else if (payload instanceof Iterable<?> values) {
            for (Object value : values) message = redactContent(message, value);
        }
        return message;
    }

    public static final class ApiException extends IOException {
        private final int status;
        private final String type;

        public ApiException(int status, String type) {
            this(status, type, "");
        }

        ApiException(int status, String type, String detail) {
            super("Managed Agents API returned HTTP " + status + " (" + type + ")"
                    + (detail.isEmpty() ? "" : ": " + detail));
            this.status = status;
            this.type = type;
        }

        public int status() {
            return status;
        }

        public String type() {
            return type;
        }
    }
}
