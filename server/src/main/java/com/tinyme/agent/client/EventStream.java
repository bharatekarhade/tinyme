package com.tinyme.agent.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single-consumer SSE reader. Closing it also closes the HTTP response body. */
public final class EventStream implements Closeable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final InputStream input;
    private final BufferedReader reader;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean firstLine = true;
    private boolean ended;

    public EventStream(InputStream input) {
        this.input = input;
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    /**
     * Blocks until the next complete JSON event, returning null at EOF.
     * Comments and metadata-only frames are skipped. An unterminated final frame
     * is discarded, as required by SSE framing. There is no automatic reconnect.
     */
    public JsonNode next() throws IOException {
        if (ended) return null;
        if (closed.get()) throw new IOException("Event stream is closed");
        StringBuilder data = new StringBuilder();
        try {
            for (;;) {
                String line = reader.readLine();
                if (line == null) {
                    ended = true;
                    close();
                    return null;
                }
                if (firstLine) {
                    firstLine = false;
                    if (line.startsWith("\uFEFF")) line = line.substring(1);
                }
                if (line.isEmpty()) {
                    if (data.isEmpty()) continue;
                    String payload = data.toString();
                    data.setLength(0);
                    if (payload.isBlank()) continue;
                    JsonNode event;
                    try {
                        event = JSON.readTree(payload);
                    } catch (RuntimeException malformed) {
                        // Parser exceptions can echo private event contents; omit them.
                        throw new IOException("Managed Agents stream contained invalid JSON");
                    }
                    if (event == null || !event.isObject()) {
                        throw new IOException("Managed Agents stream event must be a JSON object");
                    }
                    return event;
                }
                if (line.startsWith(":")) continue;
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                if (!field.equals("data")) continue;
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                data.append(value).append('\n');
            }
        } catch (IOException | RuntimeException failure) {
            try {
                close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        // Close the input directly, so another thread can cancel a blocked readLine().
        if (closed.compareAndSet(false, true)) input.close();
    }
}
