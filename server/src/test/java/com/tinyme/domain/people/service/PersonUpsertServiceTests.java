package com.tinyme.domain.people.service;

import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.people.model.upsert.PersonUpsert;
import com.tinyme.domain.people.model.upsert.UpsertOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false"
})
@Import(PersonUpsertServiceTests.DatabaseConfiguration.class)
class PersonUpsertServiceTests {
    @Autowired
    PersonService people;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM people WHERE slug LIKE 'upsert-test-%' OR slug IN ('kenji', 'kenji-2', 'kenji-3')");
    }

    @Test
    void createStoresGeneratedSlugAndStableMemoryPath() {
        UpsertOutcome.Created created = (UpsertOutcome.Created) people.upsert(
                new PersonUpsert(null, "Kenji", List.of(" Ken ", "KEN", "Kenji", " "), "friend from work"));

        assertThat(created.person()).isEqualTo(new PersonSnapshot("kenji", "Kenji", List.of("Ken"),
                "friend from work", "people/kenji.md"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM people WHERE slug = 'kenji'", Integer.class)).isEqualTo(1);
    }

    @Test
    void blankRelationshipIsStoredAsNull() {
        people.upsert(new PersonUpsert(null, "Kenji", List.of(), "  "));

        assertThat(jdbc.queryForObject("SELECT relationship FROM people WHERE slug = 'kenji'", String.class)).isNull();
        assertThat(people.upsert(new PersonUpsert(null, "Kenji", List.of(), "friend from work")))
                .isInstanceOf(UpsertOutcome.Duplicate.class);
    }

    @Test
    void sameNameAndRelationshipReturnsDuplicateButDifferentRelationshipCreatesSuffix() {
        people.upsert(new PersonUpsert(null, "Kenji", List.of(), "friend from work"));

        UpsertOutcome duplicate = people.upsert(new PersonUpsert(null, "KENJI", List.of(), "friend from work"));
        UpsertOutcome.Created cousin = (UpsertOutcome.Created) people.upsert(
                new PersonUpsert(null, "Kenji", List.of(), "cousin"));

        assertThat(duplicate).isInstanceOf(UpsertOutcome.Duplicate.class);
        assertThat(cousin.person().slug()).isEqualTo("kenji-2");
    }

    @Test
    void updateMergesAliasesReplacesRelationshipAndKeepsSlugAndMemoryPath() {
        people.upsert(new PersonUpsert(null, "Kenji", List.of("Ken"), "friend from work"));

        UpsertOutcome.Updated updated = (UpsertOutcome.Updated) people.upsert(
                new PersonUpsert("kenji", "Kenji Tanaka", List.of("KEN", "Kenny", "Kenji Tanaka"), "cousin"));

        assertThat(updated.person()).isEqualTo(new PersonSnapshot("kenji", "Kenji Tanaka",
                List.of("Ken", "Kenji", "Kenny"), "cousin", "people/kenji.md"));
        assertThat(jdbc.queryForList("SELECT unnest(aliases) FROM people WHERE slug = 'kenji'", String.class))
                .containsExactly("Ken", "Kenji", "Kenny");
        assertThat(people.get("Kenji").match()).isEqualTo(com.tinyme.domain.people.model.get.PersonMatchType.EXACT);
    }

    @Test
    void mergedAliasesAreCappedAtSixteen() {
        people.upsert(new PersonUpsert(null, "Kenji", List.of("old1", "old2", "old3", "old4",
                "old5", "old6", "old7", "old8"), null));

        UpsertOutcome.Updated updated = (UpsertOutcome.Updated) people.upsert(new PersonUpsert("kenji", "Kenji T",
                List.of("new1", "new2", "new3", "new4", "new5", "new6", "new7", "new8"), null));

        assertThat(updated.person().aliases()).hasSize(16);
        assertThat(updated.person().aliases()).contains("Kenji");
    }

    @Test
    void unknownAndSoftDeletedSlugReturnNotFound() {
        people.upsert(new PersonUpsert(null, "Kenji", List.of(), null));
        jdbc.update("UPDATE people SET deleted_at = now() WHERE slug = 'kenji'");

        assertThat(people.upsert(new PersonUpsert("missing", "Kenji", List.of(), null)))
                .isEqualTo(new UpsertOutcome.NotFound("missing"));
        assertThat(people.upsert(new PersonUpsert("kenji", "Kenji", List.of(), null)))
                .isEqualTo(new UpsertOutcome.NotFound("kenji"));
    }

    @Test
    void softDeletedSlugIsStillTakenWhenCreating() {
        people.upsert(new PersonUpsert(null, "Kenji", List.of(), "friend"));
        jdbc.update("UPDATE people SET deleted_at = now() WHERE slug = 'kenji'");

        UpsertOutcome.Created next = (UpsertOutcome.Created) people.upsert(
                new PersonUpsert(null, "Kenji", List.of(), "cousin"));

        assertThat(next.person().slug()).isEqualTo("kenji-2");
        assertThat(next.person().memoryPath()).isEqualTo("people/kenji-2.md");
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
