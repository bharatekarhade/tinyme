package com.tinyme.auth.repository;

import com.tinyme.auth.entity.DeviceTokenEntity;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class DeviceTokenRepository {
    private final DeviceTokenJpaRepository tokens;

    DeviceTokenRepository(DeviceTokenJpaRepository tokens) { this.tokens = tokens; }

    public DeviceTokenEntity save(DeviceTokenEntity token) { return tokens.save(token); }
    public Optional<DeviceTokenEntity> findLive(byte[] hash, Instant now) {
        return tokens.findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(hash, now);
    }
    public Optional<DeviceTokenEntity> findUnrevoked(byte[] hash) {
        return tokens.findByTokenHashAndRevokedAtIsNull(hash);
    }
}
