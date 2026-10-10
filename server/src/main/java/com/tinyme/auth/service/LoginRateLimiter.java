package com.tinyme.auth.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Component
public class LoginRateLimiter {
    private static final int MAX_PER_IP = 5;
    private static final int MAX_GLOBAL = 20;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, ArrayDeque<Instant>> attemptsByIp = new HashMap<>();
    private final ArrayDeque<Instant> globalAttempts = new ArrayDeque<>();
    private final Clock clock;

    public LoginRateLimiter(Clock clock) { this.clock = clock; }

    /** Atomically reserves this login attempt against both rolling limits. */
    public synchronized boolean tryAcquire(String ip) {
        Instant now = clock.instant();
        discardExpired(globalAttempts, now);
        pruneIpAttempts(now);

        String key = key(ip);
        var ipAttempts = attemptsByIp.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        if (globalAttempts.size() >= MAX_GLOBAL || ipAttempts.size() >= MAX_PER_IP) {
            if (ipAttempts.isEmpty()) attemptsByIp.remove(key);
            return false;
        }
        ipAttempts.addLast(now);
        globalAttempts.addLast(now);
        return true;
    }

    private void pruneIpAttempts(Instant now) {
        Iterator<Map.Entry<String, ArrayDeque<Instant>>> iterator = attemptsByIp.entrySet().iterator();
        while (iterator.hasNext()) {
            var attempts = iterator.next().getValue();
            discardExpired(attempts, now);
            if (attempts.isEmpty()) iterator.remove();
        }
    }

    private static void discardExpired(ArrayDeque<Instant> attempts, Instant now) {
        Instant floor = now.minus(WINDOW);
        while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(floor)) attempts.removeFirst();
    }

    private static String key(String ip) { return ip == null || ip.isBlank() ? "unknown" : ip; }
}
