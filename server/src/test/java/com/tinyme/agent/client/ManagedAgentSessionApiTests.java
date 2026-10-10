package com.tinyme.agent.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ManagedAgentSessionApiTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final URI BASE = URI.create("http://localhost");

    @Test
    void createsSessionAndSendsEventsWithExpectedBodyAndHeaders() throws Exception {
        var received = new CopyOnWriteArrayList<Map<String, String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/sessions", exchange -> {
            received.add(Map.of("method", exchange.getRequestMethod(), "path", exchange.getRequestURI().getPath(),
                    "key", exchange.getRequestHeaders().getFirst("x-api-key"),
                    "beta", exchange.getRequestHeaders().getFirst("anthropic-beta"),
                    "version", exchange.getRequestHeaders().getFirst("anthropic-version"),
                    "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (exchange.getRequestURI().getPath().endsWith("/events")) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                byte[] body = "{\"id\":\"sesn_test\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            var api = new ManagedAgentApi("test-key", base(server));
            var body = Map.of("agent", "agent_test", "environment", "env_test");
            assertThat(api.createSession(body)).isEqualTo("sesn_test");
            var events = List.of(Map.of("type", "user.message", "content",
                    List.of(Map.of("type", "text", "text", "hello"))));
            api.sendEvents("sesn_test", events);
            assertThat(received).hasSize(2).allSatisfy(request -> assertThat(request)
                    .containsEntry("method", "POST").containsEntry("key", "test-key")
                    .containsEntry("beta", "managed-agents-2026-04-01").containsEntry("version", "2023-06-01"));
            assertThat(received.get(0).get("path")).isEqualTo("/v1/sessions");
            assertThat(JSON.readTree(received.get(0).get("body"))).isEqualTo(JSON.valueToTree(body));
            assertThat(received.get(1).get("path")).isEqualTo("/v1/sessions/sesn_test/events");
            assertThat(JSON.readTree(received.get(1).get("body"))).isEqualTo(JSON.valueToTree(Map.of("events", events)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsFirstEventBeforeConnectionCloses() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var accepts = new CopyOnWriteArrayList<String>();
        server.createContext("/v1/sessions/sesn_test/events/stream", exchange -> {
            accepts.add(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write("data: {\"type\":\"session.status_running\"}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                try (var stream = new ManagedAgentApi("test", base(server)).openStream("sesn_test")) {
                    assertThat(stream.next().get("type").stringValue()).isEqualTo("session.status_running");
                }
            });
            assertThat(accepts).containsExactly("text/event-stream");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void listsEventsAfterProcessedAtInAscendingOrderAndFollowsPages() throws Exception {
        var queries = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/sessions/sesn_test/events", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            String body = exchange.getRequestURI().getRawQuery().contains("page=")
                    ? "{\"data\":[{\"id\":\"event-2\",\"type\":\"agent.message\"}],\"next_page\":null}"
                    : "{\"data\":[{\"id\":\"event-1\",\"type\":\"user.message\"}],\"next_page\":\"cursor/2\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var events = new ManagedAgentApi("test", base(server)).listEvents(
                    "sesn_test", Instant.parse("2026-10-05T15:30:01Z"));

            assertThat(events).extracting(event -> event.path("id").stringValue())
                    .containsExactly("event-1", "event-2");
            assertThat(queries).hasSize(2);
            assertThat(queries.getFirst()).contains("order=asc", "created_at%5Bgt%5D=2026-10-05T15%3A30%3A01Z");
            assertThat(queries.get(1)).contains("order=asc", "created_at%5Bgt%5D=2026-10-05T15%3A30%3A01Z",
                    "page=cursor%2F2");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void onlyTheStreamOmitsTheNormalRequestTimeout() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(200, "{\"id\":\"sesn_test\"}", "application/json"),
                response(200, "{}", "application/json"),
                response(200, input(""), "text/event-stream"))
                .when(client).send(any(HttpRequest.class), any());
        var api = new ManagedAgentApi("test", BASE, client);
        api.createSession(Map.of());
        api.sendEvents("sesn_test", List.of());
        try (var stream = api.openStream("sesn_test")) {
            assertThat(stream.next()).isNull();
        }
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(3)).send(requests.capture(), any());
        assertThat(requests.getAllValues().get(0).timeout()).contains(Duration.ofSeconds(60));
        assertThat(requests.getAllValues().get(1).timeout()).contains(Duration.ofSeconds(60));
        assertThat(requests.getAllValues().get(2).timeout()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void sendEventsNeverRetriesStatusFailures(int status) throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(status, "{\"error\":{\"type\":\"overloaded_error\"}}", "application/json"))
                .when(client).send(any(HttpRequest.class), any());
        var api = new ManagedAgentApi("test", BASE, client);
        assertThatThrownBy(() -> api.sendEvents("sesn_test", List.of(Map.of("type", "user.interrupt"))))
                .isInstanceOf(ManagedAgentApi.ApiException.class);
        verify(client).send(any(HttpRequest.class), any());
    }

    @Test
    void sendEventsNeverRetriesAnIoFailure() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doThrow(new IOException("connection lost after sending"))
                .when(client).send(any(HttpRequest.class), any());
        assertThatThrownBy(() -> new ManagedAgentApi("test", BASE, client).sendEvents("sesn_test", List.of()))
                .isInstanceOf(IOException.class);
        verify(client).send(any(HttpRequest.class), any());
    }

    @Test
    void streamHttpFailureClosesTheResponseBody() throws Exception {
        HttpClient client = mock(HttpClient.class);
        InputStream body = spy(input("{\"error\":{\"type\":\"not_found_error\"}}"));
        doReturn(response(404, body, "application/json")).when(client).send(any(HttpRequest.class), any());
        assertThatThrownBy(() -> new ManagedAgentApi("test", BASE, client).openStream("sesn_test"))
                .isInstanceOf(ManagedAgentApi.ApiException.class).hasMessageContaining("not_found_error");
        verify(body).close();
        verify(client).send(any(HttpRequest.class), any());
    }

    @Test
    void rejectsAndClosesNonSseSuccessResponse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        InputStream body = spy(input("{}"));
        doReturn(response(200, body, "application/json")).when(client).send(any(HttpRequest.class), any());
        assertThatThrownBy(() -> new ManagedAgentApi("test", BASE, client).openStream("sesn_test"))
                .isInstanceOf(IOException.class).hasMessageContaining("Content-Type");
        verify(body).close();
    }

    @Test
    void rejectsInvalidSessionResponseAndUnsafeSessionPaths() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(200, "{\"id\":\"agent_wrong\"}", "application/json"))
                .when(client).send(any(HttpRequest.class), any());
        var api = new ManagedAgentApi("test", BASE, client);
        assertThatThrownBy(() -> api.createSession(Map.of())).isInstanceOf(IOException.class)
                .hasMessageContaining("session ID");
        clearInvocations(client);
        assertThatThrownBy(() -> api.openStream("sesn_test/../agents")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.sendEvents("sesn_test?query", List.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void eventValidationErrorRedactsNestedMessageContent() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(400, "{\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"bad text: personal note sk-ant-secret\"}}", "application/json"))
                .when(client).send(any(HttpRequest.class), any());
        var api = new ManagedAgentApi("sk-ant-secret", BASE, client);
        assertThatThrownBy(() -> api.sendEvents("sesn_test", List.of(Map.of("type", "user.message", "content",
                List.of(Map.of("type", "text", "text", "personal note"))))))
                .hasMessageContaining("invalid_request_error")
                .hasMessageNotContaining("personal note").hasMessageNotContaining("sk-ant-secret");
    }

    private static URI base(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static InputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static <T> HttpResponse<T> response(int status, T body, String contentType) {
        HttpResponse<T> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (a, b) -> true));
        return response;
    }
}
