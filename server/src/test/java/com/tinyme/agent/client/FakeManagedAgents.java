package com.tinyme.agent.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Fixture-backed Managed Agents fake for deterministic turn-loop tests. */
public final class FakeManagedAgents implements ManagedAgents {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<List<String>> streamFixtures;
    private final List<List<JsonNode>> catchupFixtures;
    private final List<List<? extends Map<String, ?>>> sentEvents = new CopyOnWriteArrayList<>();
    private final List<String> operations = new CopyOnWriteArrayList<>();
    private final List<Instant> listAfterValues = new CopyOnWriteArrayList<>();
    private final Runnable beforeFirstUserMessage;
    private final IntConsumer onOpen;
    private final AtomicInteger userMessages = new AtomicInteger();
    private final AtomicInteger openCount = new AtomicInteger();
    private final AtomicInteger catchupCount = new AtomicInteger();
    private final int dropAfterEvents;
    private final Duration streamDelay;
    private final int openFailureStatus;
    private final AtomicInteger openFailuresRemaining;
    private final int listFailureStatus;
    private final AtomicInteger listFailuresRemaining;
    private final Map<String, FailurePlan> sendFailures;

    public FakeManagedAgents(List<String> fixtureEvents) {
        this(builder(fixtureEvents));
    }

    public FakeManagedAgents(List<String> fixtureEvents, Runnable beforeFirstUserMessage, IntConsumer onOpen) {
        this(new Builder(List.of(List.copyOf(fixtureEvents)), List.of(), beforeFirstUserMessage, onOpen));
    }

    private FakeManagedAgents(Builder builder) {
        this.streamFixtures = builder.streamFixtures;
        this.catchupFixtures = builder.catchupFixtures;
        this.beforeFirstUserMessage = builder.beforeFirstUserMessage;
        this.onOpen = builder.onOpen;
        this.dropAfterEvents = builder.dropAfterEvents;
        this.streamDelay = builder.streamDelay;
        this.openFailureStatus = builder.openFailureStatus;
        this.openFailuresRemaining = new AtomicInteger(builder.openFailures);
        this.listFailureStatus = builder.listFailureStatus;
        this.listFailuresRemaining = new AtomicInteger(builder.listFailures);
        this.sendFailures = builder.sendFailures;
    }

    public static Builder builder(List<String> fixtureEvents) {
        return new Builder(List.of(List.copyOf(fixtureEvents)), List.of(), () -> { }, ignored -> { });
    }

    /** Loads a JSON array of raw SSE event objects from test resources. */
    public static Builder fromResource(Class<?> anchor, String resource) throws IOException {
        Objects.requireNonNull(anchor, "anchor");
        try (var input = anchor.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing stream fixture: " + resource);
            JsonNode root = JSON.readTree(input);
            if (root == null || !root.isArray()) throw new IOException("Stream fixture must be a JSON array");
            var events = new ArrayList<String>();
            for (JsonNode event : root) {
                if (!event.isObject()) throw new IOException("Stream fixture entries must be JSON objects");
                events.add(JSON.writeValueAsString(event));
            }
            return builder(events);
        }
    }

    public static FakeManagedAgents scripted(List<List<String>> streamFixtures,
                                             List<List<JsonNode>> catchupFixtures) {
        if (streamFixtures.isEmpty()) throw new IllegalArgumentException("At least one stream fixture is required");
        return new Builder(streamFixtures.stream().map(List::copyOf).toList(),
                catchupFixtures.stream().map(List::copyOf).toList(), () -> { }, ignored -> { }).build();
    }

    @Override
    public String createSession(Map<?, ?> body) {
        return "sesn_fake";
    }

    @Override
    public void sendEvents(String sessionId, List<? extends Map<String, ?>> events) throws IOException {
        var batch = new ArrayList<Map<String, ?>>();
        for (Map<String, ?> event : events) batch.add(Map.copyOf(event));
        sentEvents.add(List.copyOf(batch));
        for (Map<String, ?> event : events) {
            String type = String.valueOf(event.get("type"));
            FailurePlan plan = sendFailures.get(type);
            if (plan != null && plan.failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw apiFailure(plan.status);
            }
            if ("user.message".equals(type) && userMessages.getAndIncrement() == 0) {
                beforeFirstUserMessage.run();
            }
        }
    }

    @Override
    public EventStream openStream(String sessionId) throws IOException {
        int number = openCount.incrementAndGet();
        operations.add("open:" + number);
        onOpen.accept(number);
        if (openFailuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw apiFailure(openFailureStatus);
        }
        List<String> fixtureEvents = streamFixtures.get(Math.min(number - 1, streamFixtures.size() - 1));
        int includedEvents = number == 1 && dropAfterEvents >= 0
                ? Math.min(dropAfterEvents, fixtureEvents.size()) : fixtureEvents.size();
        var body = new StringBuilder();
        for (int i = 0; i < includedEvents; i++) {
            body.append("data: ").append(fixtureEvents.get(i)).append("\n\n");
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        InputStream input = new ByteArrayInputStream(bytes);
        if (number == 1 && dropAfterEvents >= 0) input = new DroppedInputStream(bytes);
        if (!streamDelay.isZero()) input = new DelayedInputStream(input, streamDelay);
        return new EventStream(input);
    }

    @Override
    public List<JsonNode> listEvents(String sessionId, Instant after) throws IOException {
        operations.add("list:" + (catchupCount.get() + 1));
        listAfterValues.add(after);
        if (listFailuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw apiFailure(listFailureStatus);
        }
        int number = catchupCount.getAndIncrement();
        return number < catchupFixtures.size() ? catchupFixtures.get(number) : List.of();
    }

    public List<List<? extends Map<String, ?>>> sentEvents() {
        return List.copyOf(sentEvents);
    }

    public List<Map<String, ?>> sentEventCalls() {
        var calls = new ArrayList<Map<String, ?>>();
        for (List<? extends Map<String, ?>> batch : sentEvents) calls.addAll(batch);
        return List.copyOf(calls);
    }

    public int openCount() {
        return openCount.get();
    }

    public List<Instant> listAfterValues() {
        return List.copyOf(listAfterValues);
    }

    public List<String> operations() {
        return List.copyOf(operations);
    }

    private static ManagedAgentApi.ApiException apiFailure(int status) {
        String type = status == 429 ? "rate_limit_error" : status >= 500 ? "api_error" : "invalid_request_error";
        return new ManagedAgentApi.ApiException(status, type);
    }

    private static final class FailurePlan {
        private final int status;
        private final AtomicInteger failuresRemaining;

        private FailurePlan(int status, int times) {
            this.status = status;
            this.failuresRemaining = new AtomicInteger(times);
        }
    }

    public static final class Builder {
        private final List<List<String>> streamFixtures;
        private final List<List<JsonNode>> catchupFixtures;
        private final Runnable beforeFirstUserMessage;
        private final IntConsumer onOpen;
        private int dropAfterEvents = -1;
        private Duration streamDelay = Duration.ZERO;
        private int openFailureStatus = 0;
        private int openFailures;
        private int listFailureStatus = 0;
        private int listFailures;
        private final java.util.concurrent.ConcurrentHashMap<String, FailurePlan> sendFailures =
                new java.util.concurrent.ConcurrentHashMap<>();

        private Builder(List<List<String>> streamFixtures, List<List<JsonNode>> catchupFixtures,
                        Runnable beforeFirstUserMessage, IntConsumer onOpen) {
            if (streamFixtures.isEmpty()) throw new IllegalArgumentException("At least one stream fixture is required");
            this.streamFixtures = streamFixtures.stream().map(List::copyOf).toList();
            this.catchupFixtures = catchupFixtures.stream().map(List::copyOf).toList();
            this.beforeFirstUserMessage = beforeFirstUserMessage;
            this.onOpen = onOpen;
        }

        public Builder catchup(List<JsonNode> events) {
            return catchupSequence(List.of(events));
        }

        public Builder catchupSequence(List<List<JsonNode>> events) {
            return new Builder(streamFixtures, events, beforeFirstUserMessage, onOpen)
                    .copyOptions(this);
        }

        public Builder dropAfterEvent(int eventCount) {
            if (eventCount < 0) throw new IllegalArgumentException("eventCount must be non-negative");
            dropAfterEvents = eventCount;
            return this;
        }

        public Builder delayStream(Duration delay) {
            if (delay.isNegative()) throw new IllegalArgumentException("delay must be non-negative");
            streamDelay = delay;
            return this;
        }

        public Builder failOpenWith(int status, int times) {
            openFailureStatus = checkedStatus(status);
            openFailures = checkedTimes(times);
            return this;
        }

        public Builder failListWith(int status, int times) {
            listFailureStatus = checkedStatus(status);
            listFailures = checkedTimes(times);
            return this;
        }

        public Builder failSendWith(String eventType, int status, int times) {
            sendFailures.put(Objects.requireNonNull(eventType, "eventType"),
                    new FailurePlan(checkedStatus(status), checkedTimes(times)));
            return this;
        }

        public FakeManagedAgents build() {
            return new FakeManagedAgents(this);
        }

        private Builder copyOptions(Builder from) {
            dropAfterEvents = from.dropAfterEvents;
            streamDelay = from.streamDelay;
            openFailureStatus = from.openFailureStatus;
            openFailures = from.openFailures;
            listFailureStatus = from.listFailureStatus;
            listFailures = from.listFailures;
            sendFailures.putAll(from.sendFailures);
            return this;
        }

        private static int checkedStatus(int status) {
            if (status < 400 || status > 599) throw new IllegalArgumentException("status must be an HTTP error status");
            return status;
        }

        private static int checkedTimes(int times) {
            if (times < 1) throw new IllegalArgumentException("times must be positive");
            return times;
        }
    }

    private static final class DroppedInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private boolean dropped;

        private DroppedInputStream(byte[] bytes) {
            this.delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() throws IOException {
            if (delegate.available() == 0 && !dropped) {
                dropped = true;
                throw new IOException("Simulated stream connection reset");
            }
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (delegate.available() == 0 && !dropped) {
                dropped = true;
                throw new IOException("Simulated stream connection reset");
            }
            return delegate.read(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class DelayedInputStream extends InputStream {
        private final InputStream delegate;
        private final Duration delay;
        private boolean waited;

        private DelayedInputStream(InputStream delegate, Duration delay) {
            this.delegate = delegate;
            this.delay = delay;
        }

        private void waitOnce() throws IOException {
            if (waited) return;
            waited = true;
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Simulated stream delay was interrupted");
            }
        }

        @Override
        public int read() throws IOException {
            waitOnce();
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            waitOnce();
            return delegate.read(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
