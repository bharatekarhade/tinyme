package com.tinyme.domain.people.service;

import com.tinyme.domain.people.model.get.PeopleLookup;
import com.tinyme.domain.people.model.get.PersonMatchType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "tinyme.tools.allow-missing-handlers=true"
})
@Import(PersonServiceTests.DatabaseConfiguration.class)
class PersonServiceTests {
    @Autowired
    PersonService people;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM entries WHERE kind = 'person_test'");
        jdbc.update("DELETE FROM entry_kinds WHERE kind = 'person_test'");
        jdbc.update("DELETE FROM people WHERE slug LIKE 'person-test-%' OR slug IN ('kenji', 'kenji-2', 'kenjii')");
    }

    @Test
    void slugAndDisplayNameMatchesAreExactCaseInsensitively() {
        insertPerson("kenji", "Kenji", "{}", "friend from work");

        assertExact("kenji", "Kenji");
        assertExact("KENJI", "Kenji");
    }

    @Test
    void aliasMatchesAreExact() {
        insertPerson("kenji", "Kenji", "{Ken}", "friend from work");

        PeopleLookup result = people.get("Ken");

assertThat(result.match()).isEqualTo(PersonMatchType.EXACT);
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().getFirst().person().slug()).isEqualTo("kenji");
        assertThat(result.matches().getFirst().lastSeen()).isNull();
    }

    @Test
    void typoUsesFuzzySearch() {
        insertPerson("kenji", "Kenji", "{Ken}", "friend from work");

        PeopleLookup result = people.get("Kenzie");

assertThat(result.match()).isEqualTo(PersonMatchType.FUZZY);
        assertThat(result.matches()).extracting(match -> match.person().displayName()).containsExactly("Kenji");
    }

    @Test
    void exactResultsDoNotMixInFuzzyMatches() {
        insertPerson("kenji", "Kenji", "{}", "friend from work");
        insertPerson("kenjii", "Kenjii", "{}", "cousin");

        PeopleLookup result = people.get("Kenji");

assertThat(result.match()).isEqualTo(PersonMatchType.EXACT);
        assertThat(result.matches()).extracting(match -> match.person().displayName()).containsExactly("Kenji");
    }

    @Test
    void returnsBothPeopleWithSameDisplayNameAndRelationships() {
        insertPerson("kenji", "Kenji", "{}", "friend from work");
        insertPerson("kenji-2", "Kenji", "{}", "cousin");

        PeopleLookup result = people.get("Kenji");

assertThat(result.match()).isEqualTo(PersonMatchType.EXACT);
        assertThat(result.matches()).hasSize(2);
        assertThat(result.matches()).extracting(match -> match.person().relationship())
                .containsExactly("friend from work", "cousin");
    }

    @Test
    void deletedPeopleAreExcludedAndDistantQueriesReturnNone() {
        insertPerson("kenji", "Kenji", "{Ken}", "friend from work");
        jdbc.update("UPDATE people SET deleted_at = now() WHERE slug = 'kenji'");

assertThat(people.get("Kenji").match()).isEqualTo(PersonMatchType.NONE);
        assertThat(people.get("Zzzzzqq")).isEqualTo(new PeopleLookup(PersonMatchType.NONE, java.util.List.of()));
    }

    @Test
    void lastSeenUsesOnlyLiveEntries() {
        insertPerson("person-test-last-seen", "Last Seen", "{}", null);
        jdbc.update("INSERT INTO entry_kinds (kind) VALUES ('person_test')");
        UUID personId = jdbc.queryForObject("SELECT id FROM people WHERE slug = 'person-test-last-seen'", UUID.class);
        Instant liveTs = Instant.parse("2026-10-08T12:00:00Z");
        UUID liveEntryId = insertEntry(liveTs, LocalDate.of(2026, 10, 8));
        UUID deletedEntryId = insertEntry(liveTs.plusSeconds(3600), LocalDate.of(2026, 10, 8));
        jdbc.update("INSERT INTO entry_people (entry_id, person_id) VALUES (?, ?)", liveEntryId, personId);
        jdbc.update("INSERT INTO entry_people (entry_id, person_id) VALUES (?, ?)", deletedEntryId, personId);
        jdbc.update("UPDATE entries SET deleted_at = now() WHERE id = ?", deletedEntryId);

        PeopleLookup result = people.get("Last Seen");

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().getFirst().lastSeen()).isEqualTo(liveTs);
    }

    private void assertExact(String query, String displayName) {
        PeopleLookup result = people.get(query);
assertThat(result.match()).isEqualTo(PersonMatchType.EXACT);
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().getFirst().person().displayName()).isEqualTo(displayName);
    }

    private void insertPerson(String slug, String displayName, String aliases, String relationship) {
        jdbc.update("""
                INSERT INTO people (slug, display_name, aliases, relationship, memory_path)
                VALUES (?, ?, CAST(? AS text[]), ?, ?)
                """, slug, displayName, aliases, relationship, "people/" + slug + ".md");
    }

    private UUID insertEntry(Instant ts, LocalDate day) {
        return jdbc.queryForObject("""
                INSERT INTO entries (ts, local_day, kind)
                VALUES (CAST(? AS timestamptz), ?, 'person_test')
                RETURNING id
                """, UUID.class, ts.toString(), day);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18")
                    .asCompatibleSubstituteFor("postgres"));
        }
    }
}
