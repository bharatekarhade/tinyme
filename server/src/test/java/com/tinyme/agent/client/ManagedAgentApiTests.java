package com.tinyme.agent.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedAgentApiTests {
    @Test
    void getRetriesTransientStatusesButPostDoesNot() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agents", exchange -> {
            int count = calls.incrementAndGet();
            int status = count == 1 ? 503 : count == 2 ? 429 : 200;
            if (exchange.getRequestMethod().equals("POST")) status = 503;
            byte[] body = (status == 200 ? "{\"id\":\"agent_test\"}" : "{\"error\":{\"type\":\"overloaded_error\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var api = new ManagedAgentApi("test", URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            assertThat(api.request("GET", "/v1/agents", null, false).get("id")).isEqualTo("agent_test");
            assertThat(calls.get()).isEqualTo(3);
            assertThatThrownBy(() -> api.request("POST", "/v1/agents", Map.of(), false))
                    .isInstanceOf(ManagedAgentApi.ApiException.class);
            assertThat(calls.get()).isEqualTo(4);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validationErrorIncludesRequestContextWithoutSecrets() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/environments", exchange -> {
            byte[] body = "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"config invalid; sk-ant-secret and private-memory\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("request-id", "req_test");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var api = new ManagedAgentApi("sk-ant-secret", URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            assertThatThrownBy(() -> api.request("POST", "/v1/environments", Map.of("content", "private-memory"), false))
                    .hasMessageContaining("POST /v1/environments").hasMessageContaining("config invalid")
                    .hasMessageContaining("req_test").hasMessageNotContaining("sk-ant-secret")
                    .hasMessageNotContaining("private-memory");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void get404IsNotRetriedAndMemoryUsesSeparateBetaHeader() throws Exception {
        var calls = new AtomicInteger();
        var header = new java.util.concurrent.atomic.AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/memory_stores/missing", exchange -> {
            calls.incrementAndGet();
            header.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            byte[] body = "{\"error\":{\"type\":\"not_found_error\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var api = new ManagedAgentApi("test", URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            assertThatThrownBy(() -> api.request("GET", "/v1/memory_stores/missing", null, true))
                    .isInstanceOf(ManagedAgentApi.ApiException.class);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(header.get()).isEqualTo("agent-memory-2026-07-22");
        } finally {
            server.stop(0);
        }
    }
}
