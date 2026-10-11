package com.tinyme.api.people;

import com.tinyme.agent.client.ManagedAgents;
import com.tinyme.agent.service.AgentResources;
import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.people.service.PersonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class PersonDetailService {
    private static final Logger log = LoggerFactory.getLogger(PersonDetailService.class);

    private final PersonService people;
    private final AgentResources resources;
    private final ManagedAgents api;

    public PersonDetailService(PersonService people, AgentResources resources, ManagedAgents api) {
        this.people = Objects.requireNonNull(people, "people");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.api = Objects.requireNonNull(api, "api");
    }

    public List<PersonResponse> list() {
        return people.list().stream().map(PersonResponse::from).toList();
    }

    public Optional<PersonDetailResponse> get(String slug) {
        Optional<PersonSnapshot> found = people.findLiveBySlug(slug);
        if (found.isEmpty()) return Optional.empty();
        PersonSnapshot person = found.get();
        String memoryStoreId = resources.load().memoryStoreId();
        try {
            String content = api.readMemory(memoryStoreId, person.memoryPath()).orElse(null);
            return Optional.of(new PersonDetailResponse(PersonResponse.from(person), content, true, List.of()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Memory lookup was interrupted for person slug {}", slug);
            return Optional.of(new PersonDetailResponse(PersonResponse.from(person), null, false, List.of()));
        } catch (IOException | RuntimeException unavailable) {
            log.warn("Memory lookup failed for person slug {}", slug, unavailable);
            return Optional.of(new PersonDetailResponse(PersonResponse.from(person), null, false, List.of()));
        }
    }
}
