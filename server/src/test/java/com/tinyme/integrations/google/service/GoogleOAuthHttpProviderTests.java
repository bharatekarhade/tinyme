package com.tinyme.integrations.google.service;

import com.sun.net.httpserver.HttpServer;
import com.tinyme.integrations.google.config.GoogleOAuthProperties;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleOAuthHttpProviderTests {
    @Test
    void refreshPostsFormAndParsesRecordedTokenResponse() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"access_token\":\"recorded-access\",\"expires_in\":3599}");
        });
        try {
            GoogleOAuthHttpProvider provider = provider(server);
            var token = provider.refresh("refresh token/with+chars");

            assertThat(method).hasValue("POST");
            assertThat(contentType.get()).startsWith("application/x-www-form-urlencoded");
            assertThat(body.get()).contains("refresh_token=refresh+token%2Fwith%2Bchars")
                    .contains("grant_type=refresh_token").contains("client_id=client-id")
                    .contains("client_secret=client-secret");
            assertThat(token.value()).isEqualTo("recorded-access");
            assertThat(token.expiresInSeconds()).isEqualTo(3599);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refreshPreservesInvalidGrantCodeWithoutLeakingProviderBody() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"private details\"}"));
        try {
            GoogleOAuthHttpProvider provider = provider(server);
            assertThatThrownBy(() -> provider.refresh("refresh-token"))
                    .isInstanceOf(GoogleOAuthProvider.ProviderException.class)
                    .hasMessage("Google OAuth request failed with HTTP 400 (invalid_grant)")
                    .hasMessageNotContaining("private details")
                    .satisfies(error -> assertThat(((GoogleOAuthProvider.ProviderException) error).errorCode())
                            .isEqualTo("invalid_grant"));
        } finally {
            server.stop(0);
        }
    }

    private static GoogleOAuthHttpProvider provider(HttpServer server) {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
        return new GoogleOAuthHttpProvider(new GoogleOAuthProperties("client-id", "client-secret", "redirect"),
                HttpClient.newHttpClient(), endpoint, endpoint, endpoint);
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", handler);
        server.start();
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
