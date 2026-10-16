package com.tinyme.integrations.google.service;

import com.tinyme.integrations.google.config.GoogleOAuthProperties;
import com.tinyme.agent.bootstrap.GoogleAgentProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class GoogleOAuthService implements GoogleConnection {
    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);
    private static final URI AUTHORIZATION_ENDPOINT = URI.create("https://accounts.google.com/o/oauth2/v2/auth");
    private static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
    private static final List<String> REQUESTED_SCOPES = List.of(
            "openid", "email",
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.compose",
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
            "https://www.googleapis.com/auth/calendar.events.freebusy",
            "https://www.googleapis.com/auth/calendar.app.created");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GoogleOAuthProperties properties;
    private final GoogleOAuthStateRepository states;
    private final GoogleAccountRepository accounts;
    private final GoogleOAuthProvider provider;
    private final GoogleTokenCipher cipher;
    private final Clock clock;
    private final ObjectProvider<GoogleAgentProvisioner> agentProvisioner;

    @Autowired
    public GoogleOAuthService(GoogleOAuthProperties properties, GoogleOAuthStateRepository states,
                              GoogleAccountRepository accounts, GoogleOAuthProvider provider,
                              GoogleTokenCipher cipher, Clock clock,
                              ObjectProvider<GoogleAgentProvisioner> agentProvisioner) {
        this.properties = properties;
        this.states = states;
        this.accounts = accounts;
        this.provider = provider;
        this.cipher = cipher;
        this.clock = clock;
        this.agentProvisioner = agentProvisioner;
    }

    public String start() {
        requireConfigured();
        if (!cipher.isConfigured()) throw new GoogleOAuthException("encryption_not_configured");
        byte[] verifierBytes = randomBytes(32);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        String state = randomToken(32);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        var now = clock.instant();
        states.create(state, verifier, now.plus(STATE_LIFETIME), now);

        String query = form("client_id", properties.clientId(), "redirect_uri", properties.redirectUri(),
                "response_type", "code", "scope", String.join(" ", REQUESTED_SCOPES),
                "access_type", "offline", "prompt", "consent", "include_granted_scopes", "true",
                "code_challenge_method", "S256", "code_challenge", challenge, "state", state);
        return AUTHORIZATION_ENDPOINT + "?" + query;
    }

    public void complete(String code, String state) throws IOException, InterruptedException {
        requireConfigured();
        Optional<String> verifier = states.consume(state, clock.instant());
        if (verifier.isEmpty()) throw new GoogleOAuthException("state_invalid");

        GoogleOAuthProvider.TokenSet tokens = provider.exchange(code, verifier.get(), properties.redirectUri());
        if (tokens.refreshToken() == null || tokens.refreshToken().isBlank())
            throw new GoogleOAuthException("refresh_token_missing");
        if (tokens.scopes() == null || tokens.scopes().isEmpty())
            throw new GoogleOAuthException("scopes_missing");

        GoogleOAuthProvider.Identity identity = provider.identity(tokens.accessToken());
        if (!identity.emailVerified()) throw new GoogleOAuthException("email_not_verified");
        GoogleAccountRepository.GoogleAccount previous = accounts.find().orElse(null);
        byte[] encrypted = cipher.encrypt(tokens.refreshToken().getBytes(StandardCharsets.UTF_8));
        accounts.save(identity.email(), tokens.scopes(), encrypted, clock.instant());
        revokePreviousAccountIfDifferent(previous, identity.email());
        try {
            GoogleAgentProvisioner provisioner = agentProvisioner.getIfAvailable();
            if (provisioner != null) provisioner.sync();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            log.warn("Google connected; Gmail agent publication is pending and will be retried ({})",
                    failure.getClass().getSimpleName());
        }
    }

    public GoogleConnectionStatus status() {
        return accounts.find().map(account -> {
            String agentStatus = "ready";
            if ("connected".equals(account.status())) {
                GoogleAgentProvisioner provisioner = agentProvisioner.getIfAvailable();
                agentStatus = provisioner == null ? "pending" : provisioner.retryIfPending();
            }
            return new GoogleConnectionStatus(account.status(), account.email(), account.scopes(), agentStatus);
        }).orElseGet(GoogleConnectionStatus::disconnected);
    }

    public void discardState(String state) {
        if (state != null && !state.isBlank()) states.discard(state);
    }

    @Override
    public void disconnect() {
        states.clear();
        GoogleAccountRepository.GoogleAccount account = accounts.find().orElse(null);
        if (account == null) return;
        try {
            String refreshToken = new String(cipher.decrypt(account.encryptedRefreshToken()), StandardCharsets.UTF_8);
            provider.revoke(refreshToken);
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            // Local credentials must be removed even if Google is unreachable or the encryption key was lost.
            log.warn("Could not revoke Google authorization ({}); removing the local connection anyway",
                    failure.getClass().getSimpleName());
        } finally {
            accounts.delete();
        }
        GoogleAgentProvisioner provisioner = agentProvisioner.getIfAvailable();
        if (provisioner != null) {
            try {
                provisioner.sync();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                log.warn("Could not publish chat without the Gmail specialist after disconnect");
            } catch (Exception failure) {
                log.warn("Could not publish chat without the Gmail specialist after disconnect ({})",
                        failure.getClass().getSimpleName());
            }
        }
    }

    private void revokePreviousAccountIfDifferent(GoogleAccountRepository.GoogleAccount previous, String newEmail) {
        if (previous == null || previous.email().trim().equalsIgnoreCase(newEmail.trim())) return;
        try {
            String oldRefreshToken = new String(cipher.decrypt(previous.encryptedRefreshToken()), StandardCharsets.UTF_8);
            provider.revoke(oldRefreshToken);
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Could not revoke previous Google authorization after account switch ({})",
                    failure.getClass().getSimpleName());
        }
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) throw new GoogleOAuthException("oauth_not_configured");
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String randomToken(int length) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(length));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static String form(String... values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length; index += 2) {
            if (!result.isEmpty()) result.append('&');
            result.append(URLEncoder.encode(values[index], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(values[index + 1], StandardCharsets.UTF_8));
        }
        return result.toString();
    }
}
