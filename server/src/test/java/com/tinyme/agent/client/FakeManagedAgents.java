package com.tinyme.agent.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import tools.jackson.databind.JsonNode;

/** Fixture-backed Managed Agents fake for deterministic turn-loop tests. */
public final class FakeManagedAgents implements ManagedAgents {
    private final List<List<String>> streamFixtures;
    private final List<List<JsonNode>> catchupFixtures;
    private final List<List<? extends Map<String, ?>>> sentEvents = new ArrayList<>();
    private final List<String> operations = new ArrayList<>();
    private final Runnable beforeFirstUserMessage;
    private final IntConsumer onOpen;
    private final AtomicInteger userMessages = new AtomicInteger();
    private final AtomicInteger openCount = new AtomicInteger();
    private final AtomicInteger catchupCount = new AtomicInteger();
    private final List<Instant> listAfterValues = new ArrayList<>();

    public FakeManagedAgents(List<String> fixtureEvents) {
        this(List.of(fixtureEvents), List.of(), () -> { }, ignored -> { });
    }

    public FakeManagedAgents(List<String> fixtureEvents, Runnable beforeFirstUserMessage, IntConsumer onOpen) {
        this(List.of(fixtureEvents), List.of(), beforeFirstUserMessage, onOpen);
    }

    private FakeManagedAgents(List<List<String>> streamFixtures, List<List<JsonNode>> catchupFixtures,
                              Runnable beforeFirstUserMessage, IntConsumer onOpen) {
        this.streamFixtures = streamFixtures.stream().map(List::copyOf).toList();
        this.catchupFixtures = catchupFixtures.stream().map(List::copyOf).toList();
        this.beforeFirstUserMessage = beforeFirstUserMessage;
        this.onOpen = onOpen;
    }

    public static FakeManagedAgents scripted(List<List<String>> streamFixtures,
                                             List<List<JsonNode>> catchupFixtures) {
        return new FakeManagedAgents(streamFixtures, catchupFixtures, () -> { }, ignored -> { });
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
        int number = openCount.incrementAndGet();
        operations.add("open:" + number);
        onOpen.accept(number);
        var body = new StringBuilder();
        List<String> fixtureEvents = streamFixtures.get(Math.min(number - 1, streamFixtures.size() - 1));
        for (String event : fixtureEvents) {
            body.append("data: ").append(event).append("\n\n");
        }
        return new EventStream(new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public List<JsonNode> listEvents(String sessionId, Instant after) {
        operations.add("list:" + (catchupCount.get() + 1));
        listAfterValues.add(after);
        int number = catchupCount.getAndIncrement();
        return number < catchupFixtures.size() ? catchupFixtures.get(number) : List.of();
    }

    public List<List<? extends Map<String, ?>>> sentEvents() {
        return List.copyOf(sentEvents);
    }

    public int openCount() {
        return openCount.get();
    }

    public List<Instant> listAfterValues() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(listAfterValues));
    }

    public List<String> operations() {
        return List.copyOf(operations);
    }
}
