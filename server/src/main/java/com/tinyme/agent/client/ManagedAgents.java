package com.tinyme.agent.client;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Session operations used by the turn loop and session manager. */
public interface ManagedAgents {
    String createSession(Map<?, ?> body) throws IOException, InterruptedException;

    void sendEvents(String sessionId, List<? extends Map<String, ?>> events)
            throws IOException, InterruptedException;

    EventStream openStream(String sessionId) throws IOException, InterruptedException;
}
