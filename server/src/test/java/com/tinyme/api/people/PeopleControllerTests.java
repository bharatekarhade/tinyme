package com.tinyme.api.people;

import com.tinyme.agent.client.ManagedAgents;
import com.tinyme.agent.model.AgentResourceIds;
import com.tinyme.agent.service.AgentResources;
import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.people.service.PersonService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PeopleControllerTests {
    @Test
    void unknownSlugMapsToNotFound() throws Exception {
        PersonDetailService people = mock(PersonDetailService.class);
        when(people.get("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new PeopleController(people).get("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    }

    @Test
    void listPreservesServiceOrderAndResponseNames() {
        PersonDetailService people = mock(PersonDetailService.class);
        when(people.list()).thenReturn(List.of(
                new PersonResponse("amy", "Amy", List.of(), null),
                new PersonResponse("zara", "Zara", List.of("Z"), "friend")));

        var result = new PeopleController(people).list();
        assertThat(result).extracting(PersonResponse::displayName).containsExactly("Amy", "Zara");
        assertThat(result.getLast().relationship()).isEqualTo("friend");
    }

    @Test
    void memoryApiFailureKeepsPersonAvailableWithNullContent() throws Exception {
        PersonService people = mock(PersonService.class);
        AgentResources resources = mock(AgentResources.class);
        ManagedAgents api = mock(ManagedAgents.class);
        var snapshot = new PersonSnapshot("kenji", "Kenji", List.of("Ken"), "friend", "people/kenji.md");
        when(people.findLiveBySlug("kenji")).thenReturn(Optional.of(snapshot));
        when(resources.load()).thenReturn(new AgentResourceIds("env_test", "agent_test", 1, "memstore_test"));
        when(api.readMemory("memstore_test", "people/kenji.md")).thenThrow(new IOException("offline"));

        var result = new PersonDetailService(people, resources, api).get("kenji").orElseThrow();
        assertThat(result.person().slug()).isEqualTo("kenji");
        assertThat(result.memoryContent()).isNull();
        assertThat(result.memoryAvailable()).isFalse();
        assertThat(result.recentEntries()).isEmpty();
    }

    @Test
    void missingMemoryFileIsNullButMemoryApiIsAvailable() throws Exception {
        PersonService people = mock(PersonService.class);
        AgentResources resources = mock(AgentResources.class);
        ManagedAgents api = mock(ManagedAgents.class);
        var snapshot = new PersonSnapshot("kenji", "Kenji", List.of(), null, "people/kenji.md");
        when(people.findLiveBySlug("kenji")).thenReturn(Optional.of(snapshot));
        when(resources.load()).thenReturn(new AgentResourceIds("env_test", "agent_test", 1, "memstore_test"));
        when(api.readMemory("memstore_test", "people/kenji.md")).thenReturn(Optional.empty());

        var result = new PersonDetailService(people, resources, api).get("kenji").orElseThrow();
        assertThat(result.memoryContent()).isNull();
        assertThat(result.memoryAvailable()).isTrue();
    }
}
