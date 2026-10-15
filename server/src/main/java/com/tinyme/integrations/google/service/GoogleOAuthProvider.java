package com.tinyme.integrations.google.service;

import java.io.IOException;
import java.util.List;

public interface GoogleOAuthProvider {
    TokenSet exchange(String code, String verifier, String redirectUri) throws IOException, InterruptedException;
    Identity identity(String accessToken) throws IOException, InterruptedException;
    void revoke(String token) throws IOException, InterruptedException;

    record TokenSet(String accessToken, String refreshToken, List<String> scopes) {}
    record Identity(String email, boolean emailVerified) {}
}
