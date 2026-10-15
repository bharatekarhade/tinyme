package com.tinyme.integrations.google.service;

import java.util.List;

public record GoogleConnectionStatus(String status, String email, List<String> scopes) {
    public GoogleConnectionStatus {
        scopes = List.copyOf(scopes);
    }

    static GoogleConnectionStatus disconnected() {
        return new GoogleConnectionStatus("disconnected", null, List.of());
    }
}
