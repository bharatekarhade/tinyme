package com.tinyme.integrations.google.service;

public class GoogleOAuthException extends RuntimeException {
    private final String reason;

    public GoogleOAuthException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() { return reason; }
}
