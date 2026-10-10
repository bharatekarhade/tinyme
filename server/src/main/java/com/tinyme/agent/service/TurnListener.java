package com.tinyme.agent.service;

import com.tinyme.agent.model.TurnEvent;

/** Listener callbacks run on the turn thread and may also arrive on the caller thread during timeout or interruption. */
@FunctionalInterface
public interface TurnListener {
    TurnListener NONE = event -> { };

    void on(TurnEvent event);
}
