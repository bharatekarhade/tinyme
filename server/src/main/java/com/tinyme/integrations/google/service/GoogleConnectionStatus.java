package com.tinyme.integrations.google.service;

import java.util.List;

public record GoogleConnectionStatus(String status, String email, List<String> scopes, String agents) {
    public GoogleConnectionStatus(String status, String email, List<String> scopes) {
        this(status, email, scopes, "ready");
    }

    public GoogleConnectionStatus {
        scopes = List.copyOf(scopes);
        if (!"ready".equals(agents) && !"pending".equals(agents)) {
            throw new IllegalArgumentException("agents must be ready or pending");
        }
    }

    static GoogleConnectionStatus disconnected() {
        return new GoogleConnectionStatus("disconnected", null, List.of(), "ready");
    }
}
