package com.tinyme.integrations.google.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/** Refreshes Google access tokens and keeps them only in process memory. */
@Service
public class GoogleTokenService {
    private static final Logger log = LoggerFactory.getLogger(GoogleTokenService.class);
    private static final Duration REFRESH_EARLY_BY = Duration.ofSeconds(60);

    private final GoogleAccountRepository accounts;
    private final GoogleTokenCipher cipher;
    private final GoogleOAuthProvider provider;
    private final Clock clock;

    private String cachedAccessToken;
    private Instant cachedExpiresAt = Instant.MIN;
    private byte[] cachedAccountFingerprint;

    public GoogleTokenService(GoogleAccountRepository accounts, GoogleTokenCipher cipher,
                              GoogleOAuthProvider provider, Clock clock) {
        this.accounts = accounts;
        this.cipher = cipher;
        this.provider = provider;
        this.clock = clock;
    }

    /** Synchronized so concurrent Gmail and Calendar calls share one refresh request. */
    public synchronized String accessToken() {
        GoogleAccountRepository.GoogleAccount account = accounts.find()
                .orElseThrow(() -> new GoogleOAuthException("google_not_connected"));
        if (!"connected".equals(account.status()))
            throw new GoogleOAuthException("google_reauth_required");

        byte[] fingerprint = account.encryptedRefreshToken();
        if (cachedAccessToken != null && Arrays.equals(fingerprint, cachedAccountFingerprint)
                && cachedExpiresAt.isAfter(clock.instant().plus(REFRESH_EARLY_BY))) {
            return cachedAccessToken;
        }

        String refreshToken = new String(cipher.decrypt(fingerprint), StandardCharsets.UTF_8);
        try {
            GoogleOAuthProvider.AccessToken refreshed = provider.refresh(refreshToken);
            if (refreshed.value() == null || refreshed.value().isBlank() || refreshed.expiresInSeconds() <= 0)
                throw new GoogleOAuthException("google_token_invalid");
            cachedAccessToken = refreshed.value();
            cachedExpiresAt = clock.instant().plusSeconds(refreshed.expiresInSeconds());
            cachedAccountFingerprint = fingerprint;
            return cachedAccessToken;
        } catch (GoogleOAuthProvider.ProviderException failure) {
            if ("invalid_grant".equals(failure.errorCode())) {
                accounts.markNeedsReauth(fingerprint);
                clearCache();
                log.info("Google refresh token was rejected; account now needs reauthorization");
                throw new GoogleOAuthException("google_reauth_required");
            }
            log.warn("Google access-token refresh failed (HTTP {})", failure.statusCode());
            throw new GoogleOAuthException("google_unavailable");
        } catch (IOException failure) {
            log.warn("Google access-token refresh failed ({})", failure.getClass().getSimpleName());
            throw new GoogleOAuthException("google_unavailable");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new GoogleOAuthException("google_unavailable");
        }
    }

    public synchronized void clearCache() {
        cachedAccessToken = null;
        cachedExpiresAt = Instant.MIN;
        cachedAccountFingerprint = null;
    }

    public void requireScope(String scope) {
        GoogleAccountRepository.GoogleAccount account = accounts.find()
                .orElseThrow(() -> new GoogleOAuthException("google_not_connected"));
        if (!"connected".equals(account.status()))
            throw new GoogleOAuthException("google_reauth_required");
        if (!account.scopes().contains(scope)) throw new MissingGoogleScopeException(scope);
    }

    public static final class MissingGoogleScopeException extends RuntimeException {
        private final String scope;
        public MissingGoogleScopeException(String scope) {
            super("Google scope is not granted: " + scope);
            this.scope = scope;
        }
        public String scope() { return scope; }
    }
}
