package com.tinyme.auth.model;

import java.time.Instant;

public record DeviceTokenGrant(String token, Instant expiresAt) {}
