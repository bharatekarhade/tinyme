package com.tinyme.maintenance;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Prevents new chat turns from entering while a destructive maintenance operation runs. */
@Component
public final class MaintenanceGate {
    private final AtomicBoolean resetting = new AtomicBoolean();
    private final AtomicInteger admittedTurns = new AtomicInteger();

    public TurnPermit enterTurn() {
        synchronized (this) {
            if (resetting.get()) throw new MaintenanceInProgressException();
            admittedTurns.incrementAndGet();
            return new TurnPermit(this);
        }
    }

    /** Closes admission and returns false if a turn is already accepted or running. */
    public boolean beginMaintenance(boolean turnLockHeld) {
        synchronized (this) {
            if (turnLockHeld || admittedTurns.get() != 0 || !resetting.compareAndSet(false, true)) return false;
            return true;
        }
    }

    public void endMaintenance() {
        synchronized (this) {
            resetting.set(false);
        }
    }

    private void leaveTurn() {
        admittedTurns.decrementAndGet();
    }

    public static TurnPermit detachedPermit() { return new TurnPermit(null); }

    public static final class TurnPermit implements AutoCloseable {
        private final MaintenanceGate gate;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TurnPermit(MaintenanceGate gate) { this.gate = gate; }

        @Override public void close() {
            if (closed.compareAndSet(false, true) && gate != null) gate.leaveTurn();
        }
    }

    public static final class MaintenanceInProgressException extends RuntimeException {
        public MaintenanceInProgressException() {
            super("TinyMe is resetting, try again in a minute");
        }
    }
}
