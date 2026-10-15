package com.tinyme.agent.service;

import com.tinyme.agent.model.TurnEvent;

/** Listener callbacks run on the turn thread and may also arrive on the caller thread during timeout or interruption. */
@FunctionalInterface
public interface TurnListener {
    TurnListener NONE = event -> { };

    /** Whether this turn can deliver an action to a live phone over its SSE connection. */
    default boolean supportsDeviceActions() { return false; }

    void on(TurnEvent event);
}
