package com.tinyme.integrations.google.api;

import com.tinyme.agent.support.AgentDatabaseConfiguration;
import com.tinyme.auth.service.DeviceTokenService;
import com.tinyme.integrations.google.service.GoogleOAuthProvider;
import com.tinyme.integrations.google.service.GoogleTokenCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "tinyme.google.client-id=test-client.apps.googleusercontent.com",
        "tinyme.google.client-secret=test-client-secret",
        "tinyme.google.redirect-uri=https://tinyme.example.test/integrations/google/callback",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@AutoConfigureMockMvc
@Import(AgentDatabaseConfiguration.class)
class GoogleAuthApiTests {
    private static final String TOKEN = "google-refresh-token-must-not-be-stored-raw";
    private static final List<String> SCOPES = List.of(
            "openid", "email", "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/calendar.events");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceTokenService deviceTokens;
    @MockitoBean GoogleOAuthProvider provider;
    @MockitoBean GoogleTokenCipher cipher;

    @BeforeEach
    void clean() throws Exception {
        jdbc.update("DELETE FROM oauth_states");
        jdbc.update("DELETE FROM google_account");
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.encrypt(any())).thenAnswer(invocation -> {
            byte[] plain = invocation.getArgument(0);
            return ("encrypted:" + new String(plain, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        });
        when(cipher.decrypt(any())).thenReturn(TOKEN.getBytes(StandardCharsets.UTF_8));
        reset(provider);
    }

    @Test
    void oauthRoundTripUsesOneTimePkceStateAndStoresOnlyEncryptedRefreshToken() throws Exception {
        mvc.perform(get("/integrations/google"))
                .andExpect(status().isUnauthorized());

        String authUrl = mvc.perform(post("/integrations/google/start").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        String authorizationUrl = tools.jackson.databind.json.JsonMapper.builder().build().readTree(authUrl)
                .path("auth_url").asString();
        URI uri = URI.create(authorizationUrl);
        String state = query(uri, "state");
        assertThat(query(uri, "code_challenge_method")).isEqualTo("S256");
        assertThat(query(uri, "code_challenge")).hasSize(43);
        assertThat(query(uri, "access_type")).isEqualTo("offline");
        assertThat(query(uri, "prompt")).isEqualTo("consent");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth_states WHERE state = ?", Long.class, state)).isEqualTo(1);

        when(provider.exchange(eq("auth-code"), any(String.class),
                eq("https://tinyme.example.test/integrations/google/callback")))
                .thenReturn(new GoogleOAuthProvider.TokenSet("google-access-token", TOKEN, SCOPES));
        when(provider.identity("google-access-token"))
                .thenReturn(new GoogleOAuthProvider.Identity("owner@example.test", true));

        mvc.perform(get("/integrations/google/callback").param("code", "auth-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("tinyme://google-connected?result=success")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth_states WHERE state = ?", Long.class, state)).isZero();
        assertThat(jdbc.queryForObject("SELECT refresh_token_enc = convert_to(?, 'UTF8') FROM google_account WHERE id = 1",
                Boolean.class, TOKEN)).isFalse();
        mvc.perform(get("/integrations/google").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("connected"))
                .andExpect(jsonPath("$.email").value("owner@example.test"))
                .andExpect(jsonPath("$.scopes").isArray());

        mvc.perform(get("/integrations/google/callback").param("code", "auth-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("result=error")));
        verify(provider, times(1)).exchange(eq("auth-code"), any(String.class), any(String.class));
    }

    @Test
    void disconnectRechecksPasswordRevokesGoogleAndRemovesTheLocalToken() throws Exception {
        jdbc.update("""
                INSERT INTO google_account (email, scopes, refresh_token_enc, status, connected_at)
                VALUES ('owner@example.test', ARRAY['openid'], decode('0102', 'hex'), 'connected', now())
                """);

        mvc.perform(delete("/integrations/google").header("Authorization", bearer())
                        .contentType("application/json").content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Wrong password"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM google_account", Long.class)).isEqualTo(1);

        mvc.perform(delete("/integrations/google").header("Authorization", bearer())
                        .contentType("application/json").content("{\"password\":\"test-only-owner-password\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));

        verify(provider).revoke(TOKEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM google_account", Long.class)).isZero();
        mvc.perform(get("/integrations/google").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("disconnected"));
    }

    @Test
    void disconnectStillRemovesLocalTokenWhenGoogleRevocationFails() throws Exception {
        jdbc.update("""
                INSERT INTO google_account (email, scopes, refresh_token_enc, status, connected_at)
                VALUES ('owner@example.test', ARRAY['openid'], decode('0102', 'hex'), 'connected', now())
                """);
        doThrow(new java.io.IOException("Google unavailable")).when(provider).revoke(TOKEN);

        mvc.perform(delete("/integrations/google").header("Authorization", bearer())
                        .contentType("application/json").content("{\"password\":\"test-only-owner-password\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM google_account", Long.class)).isZero();
    }

    @Test
    void oauthCallbackRejectsUnknownStateWithoutCallingGoogle() throws Exception {
        mvc.perform(get("/integrations/google/callback").param("code", "bad-code").param("state", "unknown"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("state_invalid")));
        verifyNoInteractions(provider);
    }

    @Test
    void accessDeniedRedirectsWithoutCallingGoogle() throws Exception {
        mvc.perform(get("/integrations/google/callback").param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("reason=access_denied")));

        verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM google_account", Long.class)).isZero();
    }

    @Test
    void expiredStateIsRejectedWithoutCallingGoogle() throws Exception {
        jdbc.update("INSERT INTO oauth_states (state, code_verifier, expires_at) VALUES (?, ?, now() - interval '1 minute')",
                "expired-state", "test-verifier");

        mvc.perform(get("/integrations/google/callback").param("code", "auth-code").param("state", "expired-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("reason=state_invalid")));

        verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM google_account", Long.class)).isZero();
    }

    @Test
    void missingRefreshTokenFailsWithoutWritingAnAccount() throws Exception {
        String state = startState();
        when(provider.exchange(eq("auth-code"), any(String.class), any(String.class)))
                .thenReturn(new GoogleOAuthProvider.TokenSet("google-access-token", null, SCOPES));

        mvc.perform(get("/integrations/google/callback").param("code", "auth-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("reason=refresh_token_missing")));

        verify(provider).exchange(eq("auth-code"), any(String.class), any(String.class));
        verify(provider, never()).identity(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM google_account", Long.class)).isZero();
    }

    @Test
    void missingScopesHaveTheirOwnOAuthError() throws Exception {
        String state = startState();
        when(provider.exchange(eq("auth-code"), any(String.class), any(String.class)))
                .thenReturn(new GoogleOAuthProvider.TokenSet("google-access-token", TOKEN, List.of()));

        mvc.perform(get("/integrations/google/callback").param("code", "auth-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("reason=scopes_missing")));

        verify(provider, never()).identity(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM google_account", Long.class)).isZero();
    }

    @Test
    void reconnectingWithAnotherEmailRevokesTheOldTokenAfterSavingTheNewAccount() throws Exception {
        jdbc.update("""
                INSERT INTO google_account (email, scopes, refresh_token_enc, status, connected_at)
                VALUES ('old@example.test', ARRAY['openid'], decode('0102', 'hex'), 'connected', now())
                """);
        String state = startState();
        when(provider.exchange(eq("auth-code"), any(String.class), any(String.class)))
                .thenReturn(new GoogleOAuthProvider.TokenSet("new-access-token", "new-refresh-token", SCOPES));
        when(provider.identity("new-access-token"))
                .thenReturn(new GoogleOAuthProvider.Identity("new@example.test", true));
        doAnswer(invocation -> {
            assertThat(jdbc.queryForObject("SELECT email FROM google_account", String.class))
                    .isEqualTo("new@example.test");
            return null;
        }).when(provider).revoke(TOKEN);

        mvc.perform(get("/integrations/google/callback").param("code", "auth-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("result=success")));

        verify(provider).revoke(TOKEN);
        assertThat(jdbc.queryForObject("SELECT email FROM google_account", String.class)).isEqualTo("new@example.test");
        assertThat(new String(jdbc.queryForObject("SELECT refresh_token_enc FROM google_account", byte[].class),
                StandardCharsets.UTF_8)).isEqualTo("encrypted:new-refresh-token");
    }

    @Test
    void reconnectingSameEmailDoesNotRevokeTheGrant() throws Exception {
        jdbc.update("""
                INSERT INTO google_account (email, scopes, refresh_token_enc, status, connected_at)
                VALUES ('owner@example.test', ARRAY['openid'], decode('0102', 'hex'), 'connected', now())
                """);
        String state = startState();
        when(provider.exchange(eq("auth-code"), any(String.class), any(String.class)))
                .thenReturn(new GoogleOAuthProvider.TokenSet("google-access-token", "new-refresh-token", SCOPES));
        when(provider.identity("google-access-token"))
                .thenReturn(new GoogleOAuthProvider.Identity("OWNER@example.test", true));

        mvc.perform(get("/integrations/google/callback").param("code", "auth-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("result=success")));

        verify(provider, never()).revoke(anyString());
    }

    private String startState() throws Exception {
        String response = mvc.perform(post("/integrations/google/start").header("Authorization", bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        URI authUri = URI.create(tools.jackson.databind.json.JsonMapper.builder().build().readTree(response)
                .path("auth_url").asString());
        return query(authUri, "state");
    }

    private String bearer() {
        return "Bearer " + deviceTokens.issue("google-api-test").token();
    }

    private static String query(URI uri, String name) {
        return java.util.Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .filter(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name))
                .map(pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
    }
}
