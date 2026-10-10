package com.tinyme.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void limitsFiveAttemptsPerIpAndTwentyAcrossAllIps() {
        var perIp = new LoginRateLimiter(CLOCK);
        for (int i = 0; i < 5; i++) assertThat(perIp.tryAcquire("192.0.2.1")).isTrue();
        assertThat(perIp.tryAcquire("192.0.2.1")).isFalse();

        var global = new LoginRateLimiter(CLOCK);
        for (int i = 0; i < 20; i++) assertThat(global.tryAcquire("192.0.2." + i)).isTrue();
        assertThat(global.tryAcquire("198.51.100.1")).isFalse();
    }
}
