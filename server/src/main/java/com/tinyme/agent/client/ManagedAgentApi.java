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
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import tools.jackson.databind.JsonNode;

public final class ManagedAgentApi implements ManagedAgents {
    private static final int MAX_RETRIES = 3;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String key;
    private final URI base;
    private final Sleeper retrySleeper;
    private final LongSupplier jitterMillis;

    public ManagedAgentApi(String key, URI base) {
        this(key, base, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                Thread::sleep, () -> ThreadLocalRandom.current().nextLong(251));
    }

    ManagedAgentApi(String key, URI base, HttpClient client) {
        this(key, base, client, Thread::sleep, () -> ThreadLocalRandom.current().nextLong(251));
    }

    ManagedAgentApi(String key, URI base, HttpClient client, Sleeper retrySleeper, LongSupplier jitterMillis) {
        this.key = key;
        this.base = base;
        this.client = client;
        this.retrySleeper = retrySleeper;
        this.jitterMillis = jitterMillis;
    }

    public String createSession(Map<?, ?> body) throws IOException, InterruptedException {
        Map<?, ?> response = request("POST", "/v1/sessions", Objects.requireNonNull(body, "body"), false);
        if (!(response.get("id") instanceof String id) || !id.matches("sesn_[A-Za-z0-9_-]+")) {
            throw new IOException("Managed Agents session response is missing a valid session ID");
        }
        return id;
    }

    public void sendEvents(String sessionId, List<? extends Map<String, ?>> events) throws IOException, InterruptedException {
        // user.message is never retried after an uncertain send; 429 is always safe to retry.
        request("POST", sessionPath(sessionId) + "/events",
                Map.of("events", Objects.requireNonNull(events, "events")), false);
    }

    public EventStream openStream(String sessionId) throws IOException, InterruptedException {
        // Deliberately no HttpRequest timeout: the stream lives for the entire turn.
        var request = requestBuilder(sessionPath(sessionId) + "/events/stream", false)
                .header("Accept", "text/event-stream").GET().build();
        var response = sendWithRetry(request, true, null, HttpResponse.BodyHandlers.ofInputStream());
        InputStream body = response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.split(";", 2)[0].trim().equalsIgnoreCase("text/event-stream")) {
            body.close();
            throw new IOException("Managed Agents stream response must have Content-Type text/event-stream");
        }
        // No automatic reconnect: the caller owns turn state and event replay handling.
        return new EventStream(body);
    }

    @Override
    public List<JsonNode> listEvents(String sessionId, Instant after) throws IOException, InterruptedException {
        String sessionPath = sessionPath(sessionId) + "/events";
        String filter = "order=asc";
        if (after != null) {
            // The API's created_at filter is compared to processed_at; gte avoids skipping same-time events.
            filter += "&" + query("created_at[gte]", after.toString());
        }
        String page = null;
        Set<String> seenPages = new HashSet<>();
        List<JsonNode> events = new ArrayList<>();
        do {
            String path = sessionPath + "?" + filter + (page == null ? "" : "&" + query("page", page));
            Map<?, ?> response = request("GET", path, null, false);
            Object data = response.get("data");
            if (!(data instanceof List<?> rows)) {
                throw new IOException("Managed Agents event list response is missing data");
            }
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?>)) {
                    throw new IOException("Managed Agents event list contains an invalid event");
                }
                events.add(json.readTree(json.writeValueAsString(row)));
            }
            Object next = response.get("next_page");
            if (next == null) {
                page = null;
            } else if (next instanceof String cursor && !cursor.isBlank()) {
                if (!seenPages.add(cursor)) throw new IOException("Managed Agents event pagination repeated a cursor");
                page = cursor;
            } else {
                throw new IOException("Managed Agents event list response has an invalid next_page");
            }
        } while (page != null);
        return List.copyOf(events);
    }

    private static String query(String name, String value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
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
        boolean transientRetry = method.equals("GET") || isCustomToolResult(payload);
        var response = sendWithRetry(request, transientRetry, payload, HttpResponse.BodyHandlers.ofString());
        if (response.body().isBlank()) return Map.of();
        return json.readValue(response.body(), Map.class);
    }

    private <T> HttpResponse<T> sendWithRetry(HttpRequest request, boolean transientRetry,
                                             Map<?, ?> payload, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        for (int retry = 0; ; retry++) {
            try {
                var response = client.send(request, bodyHandler);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String responseBody;
                    if (response.body() instanceof InputStream input) {
                        try (input) {
                            responseBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    } else {
                        responseBody = String.valueOf(response.body());
                    }
                    throw apiError(response.statusCode(), response.headers(), request, responseBody, payload);
                }
                return response;
            } catch (IOException error) {
                boolean retryable = error instanceof ApiException apiError
                        ? apiError.status == 429 || (transientRetry && apiError.status >= 500 && apiError.status <= 599)
                        : transientRetry;
                if (!retryable || retry >= MAX_RETRIES) throw error;
                long backoff = 500L << retry;
                retrySleeper.sleep(backoff + Math.max(0, jitterMillis.getAsLong()));
            }
        }
    }

    private static boolean isCustomToolResult(Map<?, ?> payload) {
        if (payload == null || !(payload.get("events") instanceof List<?> events) || events.isEmpty()) return false;
        return events.stream().allMatch(event -> event instanceof Map<?, ?> map
                && "user.custom_tool_result".equals(map.get("type")));
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
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
