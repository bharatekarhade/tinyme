package com.tinyme.auth.repository;

import com.tinyme.auth.entity.DeviceTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface DeviceTokenJpaRepository extends JpaRepository<DeviceTokenEntity, UUID> {
    Optional<DeviceTokenEntity> findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(byte[] tokenHash, Instant now);
    Optional<DeviceTokenEntity> findByTokenHashAndRevokedAtIsNull(byte[] tokenHash);
}
