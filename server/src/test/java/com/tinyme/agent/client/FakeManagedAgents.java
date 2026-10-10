package com.tinyme.agent.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Fixture-backed Managed Agents fake for deterministic turn-loop tests. */
public final class FakeManagedAgents implements ManagedAgents {
    private final List<String> fixtureEvents;
    private final List<List<? extends Map<String, ?>>> sentEvents = new ArrayList<>();
    private final Runnable beforeFirstUserMessage;
    private final IntConsumer onOpen;
    private final AtomicInteger userMessages = new AtomicInteger();
    private final AtomicInteger openCount = new AtomicInteger();

    public FakeManagedAgents(List<String> fixtureEvents) {
        this(fixtureEvents, () -> { }, ignored -> { });
    }

    public FakeManagedAgents(List<String> fixtureEvents, Runnable beforeFirstUserMessage, IntConsumer onOpen) {
        this.fixtureEvents = List.copyOf(fixtureEvents);
        this.beforeFirstUserMessage = beforeFirstUserMessage;
        this.onOpen = onOpen;
    }

    @Override
    public String createSession(Map<?, ?> body) {
        return "sesn_fake";
    }

    @Override
    public void sendEvents(String sessionId, List<? extends Map<String, ?>> events) {
        sentEvents.add(List.copyOf(events));
        if (events.stream().flatMap(event -> event.values().stream())
                .anyMatch(value -> "user.message".equals(value)) && userMessages.getAndIncrement() == 0) {
            beforeFirstUserMessage.run();
        }
    }

    @Override
    public EventStream openStream(String sessionId) throws IOException {
        onOpen.accept(openCount.incrementAndGet());
        var body = new StringBuilder();
        for (String event : fixtureEvents) {
            body.append("data: ").append(event).append("\n\n");
        }
        return new EventStream(new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)));
    }

    public List<List<? extends Map<String, ?>>> sentEvents() {
        return List.copyOf(sentEvents);
    }

    public int openCount() {
        return openCount.get();
    }
}
