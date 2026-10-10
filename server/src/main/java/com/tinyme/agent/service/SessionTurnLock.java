package com.tinyme.agent.service;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/** Fair, process-local turn locks keyed by the database session row id. */
@Component
public class SessionTurnLock {
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public boolean acquire(UUID sessionRowId, Duration maxWait) throws InterruptedException {
        Objects.requireNonNull(sessionRowId, "sessionRowId");
        Objects.requireNonNull(maxWait, "maxWait");
        if (maxWait.isNegative()) throw new IllegalArgumentException("maxWait must not be negative");
        return locks.computeIfAbsent(sessionRowId, ignored -> new ReentrantLock(true))
                .tryLock(maxWait.toNanos(), TimeUnit.NANOSECONDS);
    }

    public void release(UUID sessionRowId) {
        Objects.requireNonNull(sessionRowId, "sessionRowId");
        ReentrantLock lock = locks.get(sessionRowId);
        if (lock == null || !lock.isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("Current thread does not hold the session turn lock");
        }
        lock.unlock();
    }
}
