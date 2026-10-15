package com.tinyme.integrations.google.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleApiClientsTests {
    @Test
    void gmailSearchEncodesQueryAndClampsPageSize() throws Exception {
        GoogleApiHttpClient http = mock(GoogleApiHttpClient.class);
        GmailClient gmail = new GmailClient(http);

        gmail.searchMessages("from:a@example.test newer_than:7d", 500, "page/2");

        Map<String, String> expectedParams = new LinkedHashMap<>();
        expectedParams.put("q", "from:a@example.test newer_than:7d");
        expectedParams.put("maxResults", "100");
        expectedParams.put("pageToken", "page/2");
        URI expected = GoogleApiHttpClient.uri("https://gmail.googleapis.com/gmail/v1", "/users/me/messages",
                expectedParams);
        org.mockito.Mockito.verify(http).get(expected);
    }

    @Test
    void calendarEventsRequestUsesExpandedRangeAndPageToken() throws Exception {
        GoogleApiHttpClient http = mock(GoogleApiHttpClient.class);
        CalendarClient calendar = new CalendarClient(http);
        Instant from = Instant.parse("2026-10-12T00:00:00Z");
        Instant to = Instant.parse("2026-10-19T00:00:00Z");

        calendar.listEvents("owner@example.com", from, to, "next page");

        Map<String, String> expectedParams = new LinkedHashMap<>();
        expectedParams.put("timeMin", from.toString());
        expectedParams.put("timeMax", to.toString());
        expectedParams.put("singleEvents", "true");
        expectedParams.put("orderBy", "startTime");
        expectedParams.put("pageToken", "next page");
        URI expected = GoogleApiHttpClient.uri("https://www.googleapis.com/calendar/v3",
                "/calendars/owner%40example.com/events",
                expectedParams);
        org.mockito.Mockito.verify(http).get(expected);
    }

    @Test
    void transportSendsBearerTokenAndRetriesServerErrors() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger bearerHeaders = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gmail/messages", exchange -> {
            if ("Bearer access-token".equals(exchange.getRequestHeaders().getFirst("Authorization")))
                bearerHeaders.incrementAndGet();
            if (attempts.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                byte[] response = "{\"messages\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
        try {
            GoogleTokenService tokens = mock(GoogleTokenService.class);
            when(tokens.accessToken()).thenReturn("access-token");
            GoogleApiHttpClient client = client(tokens, new ArrayList<>());

            var response = client.get(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/gmail/messages"));

            assertThat(response.path("messages").isArray()).isTrue();
            assertThat(attempts).hasValue(2);
            assertThat(bearerHeaders).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unauthorizedResponseRefreshesOnceThenRetriesWithFreshAccessToken() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (attempts.incrementAndGet() == 1) respond(exchange, 401, "{\"error\":{}}", null);
            else respond(exchange, 200, "{\"ok\":true}", null);
        });
        try {
            GoogleTokenService tokens = mock(GoogleTokenService.class);
            when(tokens.accessToken()).thenReturn("old-token", "new-token");
            GoogleApiHttpClient client = client(tokens, new ArrayList<>());

            assertThat(client.get(uri(server)).path("ok").asBoolean()).isTrue();
            assertThat(attempts).hasValue(2);
            assertThat(auth).hasValue("Bearer new-token");
            org.mockito.Mockito.verify(tokens).clearCache();
            org.mockito.Mockito.verify(tokens, org.mockito.Mockito.times(2)).accessToken();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void secondUnauthorizedResponseFailsWithoutRetryLoop() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = server(exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 401, "{\"error\":{}}", null);
        });
        try {
            GoogleTokenService tokens = mock(GoogleTokenService.class);
            when(tokens.accessToken()).thenReturn("stale-token");
            GoogleApiHttpClient client = client(tokens, new ArrayList<>());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.get(uri(server)))
                    .isInstanceOf(GoogleApiHttpClient.GoogleApiException.class)
                    .satisfies(error -> assertThat(((GoogleApiHttpClient.GoogleApiException) error).reason())
                            .isEqualTo("unauthorized"));
            assertThat(attempts).hasValue(2);
            org.mockito.Mockito.verify(tokens).clearCache();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesGoogle403RateLimitAndHonorsRetryAfter() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if (attempts.incrementAndGet() == 1)
                respond(exchange, 403, "{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}", "3");
            else respond(exchange, 200, "{\"ok\":true}", null);
        });
        try {
            GoogleTokenService tokens = mock(GoogleTokenService.class);
            when(tokens.accessToken()).thenReturn("access-token");
            List<Long> waits = new ArrayList<>();
            GoogleApiHttpClient client = client(tokens, waits);

            assertThat(client.get(uri(server)).path("ok").asBoolean()).isTrue();
            assertThat(attempts).hasValue(2);
            assertThat(waits).containsExactly(3_000L);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void insufficientPermissionsMapsToScopeMissingWithoutRetrying() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = server(exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 403,
                    "{\"error\":{\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}", null);
        });
        try {
            GoogleTokenService tokens = mock(GoogleTokenService.class);
            when(tokens.accessToken()).thenReturn("access-token");
            GoogleApiHttpClient client = client(tokens, new ArrayList<>());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.get(uri(server)))
                    .isInstanceOf(GoogleApiHttpClient.GoogleApiException.class)
                    .satisfies(error -> assertThat(((GoogleApiHttpClient.GoogleApiException) error).reason())
                            .isEqualTo("scope_missing"));
            assertThat(attempts).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesUseOneTwoFourSecondBackoffAndMapMissingScope() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = server(exchange -> {
            int current = attempts.incrementAndGet();
            if (current <= 3) respond(exchange, 403,
                    "{\"error\":{\"errors\":[{\"reason\":\"rateLimitExceeded\"}]}}", null);
            else respond(exchange, 200, "{\"ok\":true}", null);
        });
        try {
            GoogleTokenService tokens = mock(GoogleTokenService.class);
            when(tokens.accessToken()).thenReturn("access-token");
            List<Long> waits = new ArrayList<>();
            GoogleApiHttpClient client = client(tokens, waits);
            assertThat(client.get(uri(server)).path("ok").asBoolean()).isTrue();
            assertThat(waits).containsExactly(1_000L, 2_000L, 4_000L);
        } finally {
            server.stop(0);
        }

        GoogleTokenService tokens = mock(GoogleTokenService.class);
        org.mockito.Mockito.doThrow(new GoogleTokenService.MissingGoogleScopeException("gmail.readonly"))
                .when(tokens).requireScope("gmail.readonly");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client(tokens, new ArrayList<>())
                .requireScope("gmail.readonly"))
                .isInstanceOf(GoogleApiHttpClient.GoogleApiException.class)
                .satisfies(error -> assertThat(((GoogleApiHttpClient.GoogleApiException) error).reason())
                        .isEqualTo("scope_missing"));
    }

    private static GoogleApiHttpClient client(GoogleTokenService tokens, List<Long> waits) {
        return new GoogleApiHttpClient(tokens, java.net.http.HttpClient.newHttpClient(), waits::add,
                () -> 0, Clock.systemUTC());
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", handler);
        server.start();
        return server;
    }

    private static URI uri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body,
                                String retryAfter) throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (retryAfter != null) exchange.getResponseHeaders().add("Retry-After", retryAfter);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
