package com.tinyme.auth.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record LoginResponse(String token, @JsonProperty("expires_at") Instant expiresAt) {
    public static LoginResponse from(DeviceTokenGrant grant) {
        return new LoginResponse(grant.token(), grant.expiresAt());
    }
}
