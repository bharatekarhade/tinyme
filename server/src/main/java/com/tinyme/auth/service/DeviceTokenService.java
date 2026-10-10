package com.tinyme.auth.service;

import com.tinyme.auth.entity.DeviceTokenEntity;
import com.tinyme.auth.model.DeviceTokenGrant;
import com.tinyme.auth.model.Owner;
import com.tinyme.auth.repository.AccountRepository;
import com.tinyme.auth.repository.DeviceTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

@Service
public class DeviceTokenService {
    private final SecureRandom random = new SecureRandom();
    private final DeviceTokenRepository tokens;
    private final AccountRepository accounts;
    private final Clock clock;

    public DeviceTokenService(DeviceTokenRepository tokens, AccountRepository accounts, Clock clock) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public DeviceTokenGrant issue(String deviceName) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant expiresAt = clock.instant().atZone(ZoneOffset.UTC).plusYears(1).toInstant();
        String cleanedName = deviceName == null || deviceName.isBlank() ? null : deviceName.strip();
        tokens.save(DeviceTokenEntity.issue(hash(raw), cleanedName, expiresAt));
        return new DeviceTokenGrant(raw, expiresAt);
    }

    @Transactional(readOnly = true)
    public Optional<Owner> verify(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        return tokens.findLive(hash(raw), clock.instant())
                .flatMap(token -> accounts.findById((short) 1))
                .map(account -> new Owner(account.getId(), account.getEmail()));
    }

    @Transactional
    public void revoke(String raw) {
        if (raw == null || raw.isBlank()) return;
        tokens.findUnrevoked(hash(raw)).ifPresent(token -> token.revoke(clock.instant()));
    }

    static byte[] hash(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
