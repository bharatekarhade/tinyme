package com.tinyme.agent.service;

import com.tinyme.agent.client.EventStream;
import com.tinyme.agent.client.ManagedAgents;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

/** Reads live events and recovers dropped streams by reopening before querying the event history. */
public final class ResilientEventSource implements AutoCloseable {
    private static final List<Duration> BACKOFFS = List.of(
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2));

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final ManagedAgents agents;
    private final String sessionId;
    private final Sleeper sleeper;
    private final Consumer<JsonNode> observer;
    private final Set<String> seen = new HashSet<>();
    private final Queue<JsonNode> catchup = new ArrayDeque<>();
    private final Object stateLock = new Object();
    private volatile EventStream stream;
    private volatile boolean closed;
    private Instant lastSeen;
    private int failedRecoveries;

    public ResilientEventSource(ManagedAgents agents, String sessionId) throws IOException, InterruptedException {
        this(agents, sessionId, Thread::sleep, ignored -> { });
    }

    ResilientEventSource(ManagedAgents agents, String sessionId, Sleeper sleeper)
            throws IOException, InterruptedException {
        this(agents, sessionId, sleeper, ignored -> { });
    }

    ResilientEventSource(ManagedAgents agents, String sessionId, Sleeper sleeper, Consumer<JsonNode> observer)
            throws IOException, InterruptedException {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.stream = agents.openStream(sessionId);
    }

    /** Returns the next event with a previously unseen id, or fails after three unsuccessful recoveries. */
    public JsonNode next() throws IOException, InterruptedException {
        for (;;) {
            ensureOpen();
            JsonNode event = catchup.poll();
            if (event == null) {
                EventStream current = stream;
                if (current == null) throw new IOException("Event source is closed");
                try {
                    event = current.next();
                } catch (IOException dropped) {
                    recover(dropped);
                    continue;
                }
                if (event == null) {
                    recover(new IOException("Managed Agents event stream ended before end_turn"));
                    continue;
                }
            }
            observer.accept(event);
            JsonNode unseen = markAndFilter(event);
            if (unseen != null) {
                ensureOpen();
                failedRecoveries = 0;
                return unseen;
            }
        }
    }

    /** Sets a bounded history floor before the current user message is submitted. */
    public void beginTurn(Instant turnStartedAt) throws IOException {
        ensureOpen();
        lastSeen = Objects.requireNonNull(turnStartedAt, "turnStartedAt").minus(Duration.ofMinutes(1));
    }

    public Instant lastSeen() {
        return lastSeen;
    }

    private JsonNode markAndFilter(JsonNode event) throws IOException {
        JsonNode timestamp = event.get("processed_at");
        if (timestamp != null && timestamp.isString() && !timestamp.stringValue().isBlank()) {
            try {
                lastSeen = Instant.parse(timestamp.stringValue());
            } catch (RuntimeException invalidTimestamp) {
                throw new IOException("Managed Agents event has an invalid processed_at timestamp");
            }
        }
        JsonNode id = event.get("id");
        if (id == null || !id.isString() || id.stringValue().isBlank()) {
            throw new IOException("Managed Agents event is missing id");
        }
        return seen.add(id.stringValue()) ? event : null;
    }

    private void recover(IOException original) throws IOException, InterruptedException {
        ensureOpen();
        closeCurrent();
        IOException lastFailure = original;
        while (failedRecoveries < BACKOFFS.size()) {
            ensureOpen();
            Duration delay = BACKOFFS.get(failedRecoveries++);
            sleeper.sleep(delay);
            ensureOpen();
            EventStream reopened = null;
            try {
                reopened = agents.openStream(sessionId);
                ensureOpen();
                List<JsonNode> missed = agents.listEvents(sessionId, lastSeen);
                ensureOpen();
                int lastUserMessage = lastUserMessageIndex(missed);
                for (int i = 0; i <= lastUserMessage; i++) {
                    observer.accept(missed.get(i));
                    markAndFilter(missed.get(i));
                }
                synchronized (stateLock) {
                    if (closed) throw new IOException("Event source is closed");
                    stream = reopened;
                    reopened = null;
                }
                for (int i = lastUserMessage + 1; i < missed.size(); i++) catchup.add(missed.get(i));
                return;
            } catch (IOException failure) {
                lastFailure = failure;
                if (reopened != null) closeQuietly(reopened, failure);
                if (closed) throw new IOException("Event source is closed", failure);
            } catch (InterruptedException interrupted) {
                if (reopened != null) {
                    try {
                        reopened.close();
                    } catch (IOException closeFailure) {
                        interrupted.addSuppressed(closeFailure);
                    }
                }
                throw interrupted;
            }
        }
        throw new StreamLostException(lastFailure);
    }

    private static int lastUserMessageIndex(List<JsonNode> events) {
        int last = -1;
        for (int i = 0; i < events.size(); i++) {
            if ("user.message".equals(events.get(i).path("type").asString())) last = i;
        }
        return last;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Event source is closed");
    }

    private void closeCurrent() {
        EventStream current = stream;
        stream = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // The stream has already failed; continue with recovery.
            }
        }
    }

    private static void closeQuietly(EventStream stream, IOException failure) {
        try {
            stream.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    @Override
    public void close() throws IOException {
        EventStream current;
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
            current = stream;
            stream = null;
        }
        if (current != null) current.close();
    }

    public static final class StreamLostException extends IOException {
        public StreamLostException(Throwable cause) {
            super("Managed Agents event stream could not be recovered after three attempts", cause);
        }
    }
}
