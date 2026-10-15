package com.tinyme.integrations.google.service;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/** Authenticated, retrying transport shared by the Google API adapters. */
@Component
public class GoogleApiHttpClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_TRANSIENT_RETRIES = 3;
    private static final long MAX_RETRY_DELAY_MILLIS = 10_000;
    private static final long MAX_JITTER_MILLIS = 250;
    private final GoogleTokenService tokens;
    private final HttpClient http;
    private final Sleeper sleeper;
    private final LongSupplier jitterMillis;
    private final Clock clock;

    @Autowired
    public GoogleApiHttpClient(GoogleTokenService tokens) {
        this(tokens, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                Thread::sleep, () -> ThreadLocalRandom.current().nextLong(MAX_JITTER_MILLIS + 1), Clock.systemUTC());
    }

    GoogleApiHttpClient(GoogleTokenService tokens, HttpClient http) {
        this(tokens, http, Thread::sleep, () -> ThreadLocalRandom.current().nextLong(MAX_JITTER_MILLIS + 1),
                Clock.systemUTC());
    }

    GoogleApiHttpClient(GoogleTokenService tokens, HttpClient http, Sleeper sleeper,
                        LongSupplier jitterMillis, Clock clock) {
        this.tokens = tokens;
        this.http = http;
        this.sleeper = sleeper;
        this.jitterMillis = jitterMillis;
        this.clock = clock;
    }

    public JsonNode get(URI uri) throws IOException, InterruptedException {
        int transientRetries = 0;
        boolean refreshedAfterUnauthorized = false;
        while (true) {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                try {
                    return JSON.readTree(response.body());
                } catch (RuntimeException failure) {
                    throw new IOException("Google API returned invalid JSON", failure);
                }
            }

            ApiError error = parseError(response.body());
            if (status == 401 && !refreshedAfterUnauthorized) {
                tokens.clearCache();
                refreshedAfterUnauthorized = true;
                continue;
            }

            if (isTransient(status, error.reason(), error.status()) && transientRetries < MAX_TRANSIENT_RETRIES) {
                long delay = retryDelayMillis(transientRetries, response.headers().firstValue("Retry-After").orElse(null));
                transientRetries++;
                sleeper.sleep(delay);
                continue;
            }
            throw new GoogleApiException(status, responseReason(status, error));
        }
    }

    /** Sends a single non-idempotent request. POST/PUT calls deliberately have no automatic retry. */
    public JsonNode sendJson(String method, URI uri, JsonNode body) throws IOException, InterruptedException {
        String jsonBody = JSON.writeValueAsString(body);
        for (int attempt = 0; ; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                try {
                    return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
                } catch (RuntimeException failure) {
                    throw new IOException("Google API returned invalid JSON", failure);
                }
            }
            ApiError error = parseError(response.body());
            // A 401 means Google rejected the request before processing it, so one refreshed-token retry is safe.
            if (response.statusCode() == 401 && attempt == 0) {
                tokens.clearCache();
                continue;
            }
            throw new GoogleApiException(response.statusCode(), responseReason(response.statusCode(), error));
        }
    }

    public void requireScope(String scope) throws GoogleApiException {
        try {
            tokens.requireScope(scope);
        } catch (GoogleTokenService.MissingGoogleScopeException missing) {
            throw new GoogleApiException(403, "scope_missing");
        }
    }

    private boolean isTransient(int status, String reason, String googleStatus) {
        return status == 429 || status >= 500 && status <= 599
                || status == 403 && ("rateLimitExceeded".equals(reason)
                || "userRateLimitExceeded".equals(reason)
                || "RESOURCE_EXHAUSTED".equals(googleStatus));
    }

    private String responseReason(int status, ApiError error) {
        if (status == 403 && "accessNotConfigured".equals(error.reason())) return "api_not_enabled";
        if (status == 403 && ("insufficientPermissions".equals(error.reason())
                || "ACCESS_TOKEN_SCOPE_INSUFFICIENT".equals(error.reason()))) return "scope_missing";
        if (status == 401) return "unauthorized";
        return error.reason() != null ? error.reason() : error.status();
    }

    private long retryDelayMillis(int retryIndex, String retryAfter) {
        long base = retryAfterMillis(retryAfter);
        if (base < 0) base = 1_000L << retryIndex;
        long jitter = Math.max(0, Math.min(MAX_JITTER_MILLIS, jitterMillis.getAsLong()));
        return Math.min(MAX_RETRY_DELAY_MILLIS, base + jitter);
    }

    private long retryAfterMillis(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) return -1;
        try {
            return Math.max(0, Math.min(MAX_RETRY_DELAY_MILLIS,
                    Math.multiplyExact(Long.parseLong(retryAfter.trim()), 1_000L)));
        } catch (NumberFormatException | ArithmeticException ignored) {
            try {
                long delay = Duration.between(clock.instant(),
                        ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
                return Math.max(0, Math.min(MAX_RETRY_DELAY_MILLIS, delay));
            } catch (RuntimeException invalidDate) {
                return -1;
            }
        }
    }

    private static ApiError parseError(String body) {
        try {
            JsonNode error = JSON.readTree(body).get("error");
            if (error == null || error.isNull()) return new ApiError(null, null);
            JsonNode errors = error.get("errors");
            JsonNode first = errors != null && errors.isArray() && !errors.isEmpty() ? errors.get(0) : null;
            String reason = text(first, "reason");
            if (reason == null) reason = text(error, "reason");
            if (reason == null) {
                JsonNode details = error.get("details");
                if (details != null && details.isArray()) {
                    for (JsonNode detail : details) {
                        reason = text(detail, "reason");
                        if (reason != null) break;
                    }
                }
            }
            return new ApiError(reason, text(error, "status"));
        } catch (RuntimeException invalidJson) {
            return new ApiError(null, null);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    public static URI uri(String base, String path, Map<String, String> query) {
        return uri(base, path, query, Map.of());
    }

    public static URI uri(String base, String path, Map<String, String> query,
                          Map<String, ? extends java.util.List<String>> repeatedQuery) {
        StringBuilder value = new StringBuilder(base).append(path);
        if ((query != null && !query.isEmpty()) || (repeatedQuery != null && !repeatedQuery.isEmpty())) {
            StringJoiner parameters = new StringJoiner("&");
            if (query != null) query.forEach((key, item) -> parameters.add(encode(key) + "=" + encode(item)));
            if (repeatedQuery != null) repeatedQuery.forEach((key, items) -> items.forEach(
                    item -> parameters.add(encode(key) + "=" + encode(item))));
            value.append('?').append(parameters);
        }
        return URI.create(value.toString());
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    @FunctionalInterface
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private record ApiError(String reason, String status) {}

    public static final class GoogleApiException extends IOException {
        private final int statusCode;
        private final String reason;
        public GoogleApiException(int statusCode, String reason) {
            super(reason == null ? "Google API returned HTTP " + statusCode : reason);
            this.statusCode = statusCode;
            this.reason = reason;
        }
        public int statusCode() { return statusCode; }
        public String reason() { return reason; }
    }
}
