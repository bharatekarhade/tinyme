package com.tinyme.agent.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fixture-backed Managed Agents fake for deterministic turn-loop tests. */
public final class FakeManagedAgents implements ManagedAgents {
    private final List<String> fixtureEvents;
    private final List<List<? extends Map<String, ?>>> sentEvents = new ArrayList<>();

    public FakeManagedAgents(List<String> fixtureEvents) {
        this.fixtureEvents = List.copyOf(fixtureEvents);
    }

    @Override
    public String createSession(Map<?, ?> body) {
        return "sesn_fake";
    }

    @Override
    public void sendEvents(String sessionId, List<? extends Map<String, ?>> events) {
        sentEvents.add(List.copyOf(events));
    }

    @Override
    public EventStream openStream(String sessionId) throws IOException {
        var body = new StringBuilder();
        for (String event : fixtureEvents) {
            body.append("data: ").append(event).append("\n\n");
        }
        return new EventStream(new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)));
    }

    public List<List<? extends Map<String, ?>>> sentEvents() {
        return List.copyOf(sentEvents);
    }
}
