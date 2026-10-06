package com.tinyme.domain.entries.repository;

import com.tinyme.domain.entries.model.NewEntry;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "tinyme.tools.allow-missing-handlers=true"
})
@Import(EntryRepositoryTests.DatabaseConfiguration.class)
class EntryRepositoryTests {
    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntryRepository entries;

    @Test
    void insertsChatEntryAndSumsLiveQuantitiesByOptionalType() {
        String kind = "repo_drink_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        LocalDate day = LocalDate.of(2026, 10, 6);
        UUID coffeeId = null;
        UUID teaId = null;
        try {
            jdbc.update("INSERT INTO entry_kinds (kind) VALUES (?)", kind);
            coffeeId = entries.insert(new NewEntry(kind, new BigDecimal("2"), "two coffees",
                    Map.of("type", "coffee"), List.of("morning"), Instant.parse("2026-10-06T00:30:00Z"), day, "chat"));
            teaId = entries.insert(new NewEntry(kind, new BigDecimal("3"), "three teas",
                    Map.of("type", "tea"), List.of(), Instant.parse("2026-10-06T02:30:00Z"), day, "chat"));

            assertThat(coffeeId).isNotNull();
            assertThat(coffeeId.version()).isEqualTo(7);
            assertThat(jdbc.queryForObject(
                    "SELECT source FROM entries WHERE id = ?", String.class, coffeeId))
                    .isEqualTo("chat");
            assertThat(jdbc.queryForObject(
                    "SELECT tags[1] FROM entries WHERE id = ?", String.class, coffeeId))
                    .isEqualTo("morning");

            assertThat(entries.totalForDay(kind, day, null)).isEqualByComparingTo("5");
            assertThat(entries.totalForDay(kind, day, "coffee")).isEqualByComparingTo("2");
            assertThat(entries.totalForDay(kind, day, "tea")).isEqualByComparingTo("3");

            jdbc.update("UPDATE entries SET deleted_at = now() WHERE id = ?", coffeeId);
            assertThat(entries.totalForDay(kind, day, "coffee")).isEqualByComparingTo("0");
            assertThat(entries.totalForDay(kind, day, null)).isEqualByComparingTo("3");
        } finally {
            if (coffeeId != null) jdbc.update("DELETE FROM entries WHERE id = ?", coffeeId);
            if (teaId != null) jdbc.update("DELETE FROM entries WHERE id = ?", teaId);
            jdbc.update("DELETE FROM entry_kinds WHERE kind = ?", kind);
        }
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
