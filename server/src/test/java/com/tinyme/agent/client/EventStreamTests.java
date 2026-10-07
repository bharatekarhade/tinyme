package com.tinyme.agent.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventStreamTests {
    @Test
    void readsMultilineJsonAndSkipsCommentsAndMetadata() throws Exception {
        var body = new TrackedInput("\uFEFF: keepalive\r\n\r\n"
                + "event: agent.message\r\nid: sevt_1\r\n"
                + "data: {\"type\":\"agent.message\",\r\n"
                + "data: \"text\":\"コーヒー\"}\r\n\r\n"
                + ": another heartbeat\nretry: 1000\n\n"
                + "data:{\"type\":\"session.status_idle\"}\n\n");
        try (var stream = new EventStream(body)) {
            var first = stream.next();
            assertThat(first.get("type").stringValue()).isEqualTo("agent.message");
            assertThat(first.get("text").stringValue()).isEqualTo("コーヒー");
            assertThat(stream.next().get("type").stringValue()).isEqualTo("session.status_idle");
            assertThat(stream.next()).isNull();
            assertThat(stream.next()).isNull();
        }
        assertThat(body.closes).isEqualTo(1);
    }

    @Test
    void skipsEmptyDataAndDiscardsUnterminatedFrameAtEof() throws Exception {
        var body = new TrackedInput("data:\n\ndata\n\ndata: {\"type\":\"incomplete\"}\n");
        try (var stream = new EventStream(body)) {
            assertThat(stream.next()).isNull();
        }
        assertThat(body.closes).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{private-event-content", "[]", "null"})
    void invalidEventsCloseTheBodyAndDoNotExposePayload(String data) {
        var body = new TrackedInput("data: " + data + "\n\n");
        var stream = new EventStream(body);
        assertThatThrownBy(stream::next).isInstanceOf(IOException.class)
                .hasMessageNotContaining("private-event-content").hasNoCause();
        assertThat(body.closes).isEqualTo(1);
    }

    @Test
    void explicitCloseIsIdempotentAndStopsFurtherReads() throws Exception {
        var body = new TrackedInput("data: {}\n\n");
        var stream = new EventStream(body);
        stream.close();
        stream.close();
        assertThat(body.closes).isEqualTo(1);
        assertThatThrownBy(stream::next).isInstanceOf(IOException.class).hasMessageContaining("closed");
    }

    @Test
    void readFailureClosesTheBody() {
        boolean[] closed = {false};
        var stream = new EventStream(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection lost");
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        });
        assertThatThrownBy(stream::next).isInstanceOf(IOException.class).hasMessage("connection lost");
        assertThat(closed[0]).isTrue();
    }

    private static class TrackedInput extends ByteArrayInputStream {
        private int closes;

        TrackedInput(String text) {
            super(text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            closes++;
            super.close();
        }
    }
}
