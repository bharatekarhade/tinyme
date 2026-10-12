package com.tinyme.agent.client;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeManagedAgentsTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void loadsAllNamedScenarioFixturesAsRawEvents() throws Exception {
        for (String name : List.of("two-tools-one-message", "query-then-update", "no-tool-reply", "people-upsert")) {
            var fake = FakeManagedAgents.fromResource(getClass(), "/streams/" + name + ".json").build();
            try (var stream = fake.openStream("sesn_test")) {
                assertThat(stream.next().path("type").asString()).isEqualTo("user.message");
            }
        }
    }

    @Test
    void canDropAfterAnEventAndDelayTheStream() throws Exception {
        var fake = FakeManagedAgents.fromResource(getClass(), "/streams/no-tool-reply.json")
                .dropAfterEvent(1).delayStream(Duration.ofMillis(5)).build();
        try (var stream = fake.openStream("sesn_test")) {
            assertThat(stream.next().path("id").asString()).isEqualTo("no-tool-user");
            assertThatThrownBy(stream::next).isInstanceOf(java.io.IOException.class)
                    .hasMessage("Simulated stream connection reset");
        }
    }

    @Test
    void canReturnScripted429And500ResponsesAndRecordsTheCalls() throws Exception {
        var fake = FakeManagedAgents.builder(List.of())
                .failSendWith("user.message", 429, 1)
                .failSendWith("user.custom_tool_result", 500, 1)
                .build();

        assertThatThrownBy(() -> fake.sendEvents("sesn_test", List.of(Map.of("type", "user.message"))))
                .isInstanceOf(ManagedAgentApi.ApiException.class)
                .satisfies(error -> assertThat(((ManagedAgentApi.ApiException) error).status()).isEqualTo(429));
        assertThatThrownBy(() -> fake.sendEvents("sesn_test", List.of(Map.of("type", "user.custom_tool_result"))))
                .isInstanceOf(ManagedAgentApi.ApiException.class)
                .satisfies(error -> assertThat(((ManagedAgentApi.ApiException) error).status()).isEqualTo(500));

        assertThat(fake.sentEvents()).hasSize(2);
        assertThat(fake.sentEventCalls().stream().map(event -> (String) event.get("type")).toList())
                .containsExactly("user.message", "user.custom_tool_result");
    }

    @Test
    void fakeSupportsMemoryLifecycleAndSessionDeletion() throws Exception {
        var fake = FakeManagedAgents.builder(List.of())
                .memory("me/profile.md", "Existing profile")
                .build();

        var existing = fake.listMemories("memstore_test", "/me/", null);
        assertThat(existing.memories()).hasSize(1);
        assertThat(fake.getMemory("memstore_test", existing.memories().getFirst().id())).contains("Existing profile");

        fake.createMemory("memstore_test", "/people/kenji.md", "Kenji notes");
        var all = fake.listMemories("memstore_test", "/", null).memories();
        assertThat(all).extracting(MemoryRef::path).containsExactly("/me/profile.md", "/people/kenji.md");
        var kenji = all.get(1);
        assertThat(fake.getMemory("memstore_test", kenji.id())).contains("Kenji notes");

        fake.deleteMemory("memstore_test", kenji.id());
        assertThat(fake.getMemory("memstore_test", kenji.id())).isEmpty();
        assertThat(fake.listMemories("memstore_test", "/", null).memories()).extracting(MemoryRef::path)
                .containsExactly("/me/profile.md");

        fake.deleteSession("sesn_test");
        assertThat(fake.deletedSessions()).containsExactly("sesn_test");
    }
}
