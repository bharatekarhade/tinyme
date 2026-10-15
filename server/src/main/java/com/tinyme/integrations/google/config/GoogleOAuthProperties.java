package com.tinyme.integrations.google.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tinyme.google")
public record GoogleOAuthProperties(String clientId, String clientSecret, String redirectUri) {
    public boolean isConfigured() {
        return present(clientId) && present(clientSecret) && present(redirectUri);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
