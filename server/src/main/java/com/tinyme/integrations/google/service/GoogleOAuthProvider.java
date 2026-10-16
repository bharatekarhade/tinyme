package com.tinyme.integrations.google.service;

import java.io.IOException;
import java.util.List;

public interface GoogleOAuthProvider {
    TokenSet exchange(String code, String verifier, String redirectUri) throws IOException, InterruptedException;
    AccessToken refresh(String refreshToken) throws IOException, InterruptedException;
    Identity identity(String accessToken) throws IOException, InterruptedException;
    void revoke(String token) throws IOException, InterruptedException;

    record TokenSet(String accessToken, String refreshToken, List<String> scopes) {}
    record AccessToken(String value, long expiresInSeconds) {}
    record Identity(String email, boolean emailVerified) {}

    final class ProviderException extends IOException {
        private final int statusCode;
        private final String errorCode;

        public ProviderException(int statusCode, String errorCode) {
            super("Google OAuth request failed with HTTP " + statusCode
                    + (errorCode == null ? "" : " (" + errorCode + ")"));
            this.statusCode = statusCode;
            this.errorCode = errorCode;
        }

        public int statusCode() { return statusCode; }
        public String errorCode() { return errorCode; }
    }
}
