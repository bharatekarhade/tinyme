package com.tinyme.domain.people.service;

import com.tinyme.domain.people.model.get.PeopleLookup;
import com.tinyme.domain.people.model.get.PersonCandidate;
import com.tinyme.domain.people.model.get.PersonMatch;
import com.tinyme.domain.people.model.get.PersonMatchType;
import com.tinyme.domain.people.repository.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

@Service
public class PersonService {
    private final PersonRepository people;

    public PersonService(PersonRepository people) {
        this.people = Objects.requireNonNull(people, "people");
    }

    @Transactional(readOnly = true)
    public PeopleLookup get(String query) {
        Objects.requireNonNull(query, "query");
        String normalizedQuery = query.strip();
        if (normalizedQuery.isBlank()) throw new IllegalArgumentException("query is required");

        List<PersonCandidate> candidates = people.findExact(normalizedQuery, Slugger.base(normalizedQuery));
        PersonMatchType match = PersonMatchType.EXACT;
        if (candidates.isEmpty()) {
            candidates = people.findFuzzy(normalizedQuery, PeopleLookup.MAX_MATCHES);
            match = candidates.isEmpty() ? PersonMatchType.NONE : PersonMatchType.FUZZY;
        }
        if (candidates.isEmpty()) return new PeopleLookup(PersonMatchType.NONE, List.of());

        List<UUID> personIds = candidates.stream().map(PersonCandidate::id).toList();
        Map<UUID, Instant> lastSeen = people.lastSeen(personIds);
        List<PersonMatch> matches = candidates.stream()
                .map(candidate -> new PersonMatch(candidate.person(), lastSeen.get(candidate.id())))
                .toList();
        return new PeopleLookup(match, matches);
    }
}
