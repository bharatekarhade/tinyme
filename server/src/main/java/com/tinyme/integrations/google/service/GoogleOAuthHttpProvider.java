package com.tinyme.integrations.google.service;

import com.tinyme.integrations.google.config.GoogleOAuthProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Small OAuth-only HTTP adapter. Gmail and Calendar API clients are a later build step. */
@Component
public class GoogleOAuthHttpProvider implements GoogleOAuthProvider {
    private static final URI TOKEN_URL = URI.create("https://oauth2.googleapis.com/token");
    private static final URI USERINFO_URL = URI.create("https://openidconnect.googleapis.com/v1/userinfo");
    private static final URI REVOKE_URL = URI.create("https://oauth2.googleapis.com/revoke");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GoogleOAuthProperties properties;
    private final HttpClient http;

    @Autowired
    public GoogleOAuthHttpProvider(GoogleOAuthProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    GoogleOAuthHttpProvider(GoogleOAuthProperties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    @Override
    public TokenSet exchange(String code, String verifier, String redirectUri) throws IOException, InterruptedException {
        String form = form("code", code, "code_verifier", verifier, "client_id", properties.clientId(),
                "client_secret", properties.clientSecret(), "redirect_uri", redirectUri,
                "grant_type", "authorization_code");
        JsonNode response = send(HttpRequest.newBuilder(TOKEN_URL)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build());
        String accessToken = text(response, "access_token");
        String refreshToken = text(response, "refresh_token");
        String scopeText = text(response, "scope");
        List<String> scopes = scopeText == null ? List.of() : Arrays.stream(scopeText.split("\\s+"))
                .filter(scope -> !scope.isBlank()).distinct().sorted().toList();
        if (accessToken == null) throw new IOException("Google returned an incomplete token response");
        return new TokenSet(accessToken, refreshToken, scopes);
    }

    @Override
    public Identity identity(String accessToken) throws IOException, InterruptedException {
        JsonNode response = send(HttpRequest.newBuilder(USERINFO_URL)
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + accessToken).GET().build());
        String email = text(response, "email");
        boolean verified = response.path("email_verified").asBoolean(false);
        if (email == null || email.isBlank()) throw new IOException("Google did not return an account email");
        return new Identity(email, verified);
    }

    @Override
    public void revoke(String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(REVOKE_URL)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form("token", token))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Google token revocation returned HTTP " + response.statusCode());
        }
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Google OAuth request returned HTTP " + response.statusCode());
        }
        try {
            return JSON.readTree(response.body());
        } catch (RuntimeException exception) {
            throw new IOException("Google OAuth returned invalid JSON", exception);
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String form(String... values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length; index += 2) {
            if (!result.isEmpty()) result.append('&');
            result.append(encode(values[index])).append('=').append(encode(values[index + 1]));
        }
        return result.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
