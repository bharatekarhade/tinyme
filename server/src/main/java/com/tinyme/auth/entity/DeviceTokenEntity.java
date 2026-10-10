package com.tinyme.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class DeviceTokenEntity {
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true, columnDefinition = "bytea")
    private byte[] tokenHash;

    @Column(name = "device_name")
    private String deviceName;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected DeviceTokenEntity() {}

    public static DeviceTokenEntity issue(byte[] tokenHash, String deviceName, Instant expiresAt) {
        var token = new DeviceTokenEntity();
        token.tokenHash = tokenHash.clone();
        token.deviceName = deviceName;
        token.expiresAt = expiresAt;
        return token;
    }

    public UUID getId() { return id; }
    public Instant getExpiresAt() { return expiresAt; }
    public void revoke(Instant now) { if (revokedAt == null) revokedAt = now; }
}
