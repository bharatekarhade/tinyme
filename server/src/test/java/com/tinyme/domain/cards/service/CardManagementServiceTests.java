package com.tinyme.domain.cards.service;

import com.tinyme.domain.cards.model.CardSpecWriter;
import com.tinyme.domain.cards.repository.CardRepository;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(CardManagementServiceTests.DatabaseConfiguration.class)
class CardManagementServiceTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final String SPEC = """
            {"v":1,"title":"Spending","icon":"wallet","accent":"sunny","tone":"surface","blocks":[
             {"type":"stat","label":"spent","metric":{"kinds":["expense"],"op":"sum","field":"amount"},"period":"range","unit":"JPY"},
             {"type":"breakdown","label":"by category","metric":{"kinds":["expense"],"op":"sum","field":"amount"},"period":"range","by":"category","top":3}
            ]}
            """;

    @Autowired CardManagementService service;
    @Autowired CardRepository cards;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM cards WHERE slug IN ('spending', 'workouts')");
        jdbc.update("DELETE FROM entries WHERE kind = 'expense'");
        jdbc.update("UPDATE entry_kinds SET merged_into = NULL WHERE kind = 'expense'");
        jdbc.update("DELETE FROM entry_kinds WHERE kind = 'expense'");
        jdbc.update("DELETE FROM entry_kinds WHERE kind = 'workout'");
    }

    @Test
    void createsUnknownKindDraftWithFieldHintsAndDuplicateGuard() {
        var created = (CardManagementService.SaveOutcome.Saved)
                service.save(null, JSON.readTree(SPEC), false, NOW);

        assertThat(created.created()).isTrue();
        assertThat(created.activatedNow()).isFalse();
        assertThat(created.card().slug()).isEqualTo("spending");
        assertThat(created.card().status()).isEqualTo("draft");
        assertThat(created.warnings()).contains("no expense entries yet; they will count once logged");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entry_kinds WHERE kind = 'expense'", Long.class)).isZero();

        var duplicate = (CardManagementService.SaveOutcome.Duplicate)
                service.save(null, JSON.readTree(SPEC), false, NOW);
        assertThat(duplicate.existingId()).isEqualTo(created.card().id());
        assertThat(service.save(null, JSON.readTree(SPEC.replace("Spending", "Journal")), false, NOW))
                .isInstanceOf(CardManagementService.SaveOutcome.SystemCard.class);
    }

    @Test
    void updatesSameIdActivatesAndListsWithoutTheSystemCardThenDeletesIdempotently() {
        var created = (CardManagementService.SaveOutcome.Saved)
                service.save(null, JSON.readTree(SPEC), false, NOW);
        var updatedSpec = JSON.readTree(SPEC.replace("Spending", "Weekly Spending"));
        var updated = (CardManagementService.SaveOutcome.Saved)
                service.save(created.card().id(), updatedSpec, true, NOW);

        assertThat(updated.created()).isFalse();
        assertThat(updated.activatedNow()).isTrue();
        assertThat(updated.card().id()).isEqualTo(created.card().id());
        assertThat(updated.card().slug()).isEqualTo("spending");
        assertThat(updated.card().title()).isEqualTo("Weekly Spending");
        assertThat(updated.card().status()).isEqualTo("active");
        assertThat(jdbc.queryForObject("SELECT use_count FROM entry_kinds WHERE kind = 'expense'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT data_hints ->> 'amount' FROM entry_kinds WHERE kind = 'expense'", String.class))
                .isEqualTo("number");
        assertThat(jdbc.queryForObject("SELECT data_hints ->> 'category' FROM entry_kinds WHERE kind = 'expense'", String.class))
                .isEqualTo("string");
        assertThat(service.list()).extracting(item -> item.get("slug")).containsExactly("spending");
        assertThat(service.delete(created.card().id(), NOW)).isEqualTo(CardManagementService.DeleteOutcome.DELETED);
        assertThat(service.delete(created.card().id(), NOW.plusSeconds(5)))
                .isEqualTo(CardManagementService.DeleteOutcome.DELETED);
        assertThat(cards.findById(created.card().id())).isEmpty();
        var journal = cards.findBySlug("journal").orElseThrow();
        assertThat(service.delete(journal.getId(), NOW)).isEqualTo(CardManagementService.DeleteOutcome.SYSTEM_CARD);
    }

    @Test
    void warnsWhenKnownKindsHaveNotStoredFieldsUsedByTheCard() {
        jdbc.update("INSERT INTO entry_kinds (kind) VALUES ('expense')");

        var saved = (CardManagementService.SaveOutcome.Saved)
                service.save(null, JSON.readTree(SPEC), false, NOW);

        assertThat(saved.warnings()).contains("no expense entries have data.amount yet",
                "no expense entries have data.category yet");
    }

    @Test
    void latestFieldsUseAnyHintsWhenTheCardIsActivated() {
        String latestSpec = """
                {"v":1,"title":"Workouts","icon":"dumbbell","accent":"ink","tone":"surface",
                 "blocks":[{"type":"latest","label":"Last workout","kinds":["workout"],
                            "fields":["type","minutes"]}]}
                """;
        var draft = (CardManagementService.SaveOutcome.Saved)
                service.save(null, JSON.readTree(latestSpec), false, NOW);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM entry_kinds WHERE kind = 'workout'", Long.class)).isZero();

        service.save(draft.card().id(), null, true, NOW);

        assertThat(jdbc.queryForObject("SELECT data_hints ->> 'type' FROM entry_kinds WHERE kind = 'workout'", String.class))
                .isEqualTo("any");
        assertThat(jdbc.queryForObject("SELECT data_hints ->> 'minutes' FROM entry_kinds WHERE kind = 'workout'", String.class))
                .isEqualTo("any");
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
