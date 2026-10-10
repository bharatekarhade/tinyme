package com.tinyme.domain.people.service;

import com.tinyme.domain.people.model.get.PeopleLookup;
import com.tinyme.domain.people.model.get.PersonCandidate;
import com.tinyme.domain.people.model.get.PersonMatch;
import com.tinyme.domain.people.model.get.PersonMatchType;
import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.people.model.upsert.PersonUpsert;
import com.tinyme.domain.people.model.upsert.UpsertOutcome;
import com.tinyme.domain.people.repository.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.stream.Stream;

@Service
public class PersonService {
    private static final int MAX_ALIASES = 16;

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

    @Transactional
    public UpsertOutcome upsert(PersonUpsert command) {
        Objects.requireNonNull(command, "command");
        if (command.slug() != null) return update(command);

        for (PersonSnapshot existing : people.findLiveByDisplayName(command.displayName())) {
            if (existing.relationship() == null || command.relationship() == null
                    || existing.relationship().equalsIgnoreCase(command.relationship())) {
                return new UpsertOutcome.Duplicate(existing);
            }
        }

        String base = Slugger.base(command.displayName());
        String slug = Slugger.next(base, people.takenSlugs(base));
        return new UpsertOutcome.Created(people.insert(command, slug));
    }

    private UpsertOutcome update(PersonUpsert command) {
        PersonSnapshot existing = people.findLiveBySlug(command.slug()).orElse(null);
        if (existing == null) return new UpsertOutcome.NotFound(command.slug());

        List<String> addedAliases = Stream.concat(Stream.of(existing.displayName()), command.aliases().stream()).toList();
        List<String> aliases = mergeAliases(existing.aliases(), addedAliases, command.displayName());
        String relationship = command.relationship() == null ? existing.relationship() : command.relationship();
        return people.update(command.slug(), command.displayName(), aliases, relationship)
                .<UpsertOutcome>map(UpsertOutcome.Updated::new)
                .orElseGet(() -> new UpsertOutcome.NotFound(command.slug()));
    }

    private static List<String> mergeAliases(List<String> existing, List<String> added, String displayName) {
        List<String> aliases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String alias : Stream.concat(existing.stream(), added.stream()).toList()) {
            if (alias == null || alias.isBlank() || alias.equalsIgnoreCase(displayName)) continue;
            if (aliases.size() < MAX_ALIASES && seen.add(alias.toLowerCase(Locale.ROOT))) aliases.add(alias);
        }
        return List.copyOf(aliases);
    }
}
