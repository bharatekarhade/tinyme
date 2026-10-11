package com.tinyme.domain.cards.service;

import com.tinyme.domain.cards.entity.CardEntity;
import com.tinyme.domain.cards.model.CardSpecParser;
import com.tinyme.domain.cards.model.CardSpecWriter;
import com.tinyme.domain.cards.model.CardStatus;
import com.tinyme.domain.cards.model.view.BreakdownView;
import com.tinyme.domain.cards.model.view.ChartView;
import com.tinyme.domain.cards.model.view.CheckinsView;
import com.tinyme.domain.cards.model.view.GoalsView;
import com.tinyme.domain.cards.model.view.LatestView;
import com.tinyme.domain.cards.model.view.ProgressView;
import com.tinyme.domain.cards.model.view.StatView;
import com.tinyme.domain.cards.repository.CardRepository;
import com.tinyme.domain.entries.model.add.AddCommand;
import com.tinyme.domain.entries.model.aggregate.Range;
import com.tinyme.domain.entries.service.EntryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(CardEvaluatorIntegrationTests.DatabaseConfiguration.class)
class CardEvaluatorIntegrationTests {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);
    private static final String CARD_PREFIX = "evaluator-it-" + UUID.randomUUID() + "-";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired CardEvaluator evaluator;
    @Autowired EntryService entries;
    @Autowired CardRepository cards;
    @Autowired CardSpecParser parser;
    @Autowired CardSpecWriter writer;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM entries WHERE kind IN ('meal', 'drink', 'expense', 'workout')");
        jdbc.update("UPDATE entry_kinds SET merged_into = NULL WHERE kind IN ('meal', 'drink', 'expense', 'workout')");
        jdbc.update("DELETE FROM entry_kinds WHERE kind IN ('meal', 'drink', 'expense', 'workout')");
        jdbc.update("DELETE FROM cards WHERE slug LIKE ?", CARD_PREFIX + "%");
    }

    @Test
    void evaluatesTheFourExampleCardsAgainstPostgresForAWeekRange() {
        add("meal", WEDNESDAY, LocalTime.of(9, 0), Map.of("kcal", 100, "protein_g", 40, "carbs_g", 20,
                "fat_g", 10, "type", "breakfast"));
        add("drink", WEDNESDAY, LocalTime.of(0, 30), Map.of("kcal", 55, "protein_g", 5, "carbs_g", 10,
                "fat_g", 2, "type", "coffee"));
        add("drink", LocalDate.of(2026, 10, 4), LocalTime.of(12, 0), Map.of("kcal", 100));

        add("expense", LocalDate.of(2026, 10, 5), LocalTime.NOON, Map.of("amount", 8470, "category", "food"));
        add("expense", LocalDate.of(2026, 10, 6), LocalTime.NOON, Map.of("amount", 4050, "category", "transport"));
        add("expense", WEDNESDAY, LocalTime.of(13, 0), Map.of("amount", 3320, "category", "shopping"));
        add("expense", WEDNESDAY, LocalTime.of(14, 0), Map.of("amount", 2580));
        add("expense", LocalDate.of(2026, 9, 28), LocalTime.NOON, Map.of("amount", 20930, "category", "food"));
        // A previous-period comparison on Wednesday must exclude the prior Sunday's entry.
        add("expense", LocalDate.of(2026, 10, 4), LocalTime.NOON, Map.of("amount", 7000, "category", "food"));

        add("workout", LocalDate.of(2026, 10, 5), LocalTime.of(8, 0), Map.of("type", "Leg day", "minutes", 50));
        add("workout", LocalDate.of(2026, 10, 6), LocalTime.of(8, 0), Map.of("type", "Run", "minutes", 30));
        add("workout", WEDNESDAY, LocalTime.of(8, 0), Map.of("type", "Push day", "minutes", 70));

        var journal = evaluator.evaluate(cards.findBySlug("journal").orElseThrow(), Range.WEEK);
        var journalStat = (StatView) journal.blocks().getFirst();
        var journalChart = (ChartView) journal.blocks().get(1);
        assertThat(journalStat.value()).isEqualByComparingTo("9"); // ["*"] leaves out the kind predicate.
        assertThat(journalChart.points()).hasSize(7);
        assertThat(journalChart.points().getFirst().period()).isEqualTo(LocalDate.of(2026, 10, 5)); // Monday start.
        assertThat(journalChart.points().getFirst().value()).isEqualByComparingTo("2");
        assertThat(journalChart.points().getFirst().state()).isEqualTo("past");
        assertThat(journalChart.points().get(2).period()).isEqualTo(WEDNESDAY);
        assertThat(journalChart.points().get(2).state()).isEqualTo("current");
        assertThat(journalChart.points().get(3).state()).isEqualTo("future");
        assertThat(journalChart.points().getLast().value()).isEqualByComparingTo("0");

        var calories = evaluator.evaluate(saveExample("calories", CALORIES), Range.WEEK);
        var calorieProgress = (ProgressView) calories.blocks().getFirst();
        assertThat(calorieProgress.value()).isEqualByComparingTo("155");
        assertThat(calorieProgress.averageDay().baseline()).isEqualByComparingTo("3.33");
        assertThat(calorieProgress.averageDay().currentPerDay()).isEqualByComparingTo("155.00");
        assertThat(((GoalsView) calories.blocks().get(1)).items()).hasSize(3);
        assertThat(((GoalsView) calories.blocks().get(1)).items().getFirst().value()).isEqualByComparingTo("45");

        var spending = evaluator.evaluate(saveExample("spending", SPENDING), Range.WEEK);
        assertThat(((StatView) spending.blocks().getFirst()).value()).isEqualByComparingTo("18420");
        assertThat(((StatView) spending.blocks().getFirst()).previous()).isEqualByComparingTo("20930");
        var breakdown = (BreakdownView) spending.blocks().get(1);
        assertThat(breakdown.total()).isEqualByComparingTo("18420");
        assertThat(breakdown.slices()).hasSize(3);
        assertThat(breakdown.other().value()).isEqualByComparingTo("2580");

        var gym = evaluator.evaluate(saveExample("gym", GYM), Range.WEEK);
        var checkins = (CheckinsView) gym.blocks().getFirst();
        assertThat(checkins.slots()).hasSize(7);
        assertThat(checkins.slots().getFirst().period()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(checkins.doneCount()).isEqualTo(3);
        assertThat(checkins.slots().get(2).period()).isEqualTo(WEDNESDAY);
        assertThat(checkins.slots().get(2).state()).isEqualTo("current");
        assertThat(checkins.slots().get(3).state()).isEqualTo("future");
        assertThat(checkins.slots().getLast().done()).isFalse();
        var latest = (LatestView) gym.blocks().get(1);
        assertThat(latest.entry().data()).containsEntry("type", "Push day").containsEntry("minutes", 70);

        assertThat(jdbc.queryForObject("SELECT local_day FROM entries WHERE kind = 'drink' AND local_day = ?",
                LocalDate.class, WEDNESDAY))
                .isEqualTo(WEDNESDAY);
    }

    private CardEntity saveExample(String slug, String source) {
        var spec = parser.parse(JSON.readTree(source));
        Map<String, Object> stored = JSON.convertValue(writer.write(spec), MAP_TYPE);
        return cards.save(new CardEntity(CARD_PREFIX + slug, spec.title(), stored, CardStatus.DRAFT, false, 0, null));
    }

    private void add(String kind, LocalDate day, LocalTime time, Map<String, Object> data) {
        Instant instant = day.atTime(time).atZone(TOKYO).toInstant();
        entries.add(new AddCommand(kind, BigDecimal.ONE, null, data, List.of(), instant, TOKYO, "chat"));
    }

    private static final String CALORIES = """
            {"v":1,"title":"Calories","icon":"flame","accent":"coral","tone":"surface","blocks":[
              {"type":"progress","label":"kcal","style":"gauge","metric":{"kinds":["meal","drink"],"op":"sum","field":"kcal"},"period":"today","goal":2200,"unit":"kcal"},
              {"type":"goals","period":"today","items":[
                {"label":"Protein","metric":{"kinds":["meal","drink"],"op":"sum","field":"protein_g"},"goal":120,"unit":"g"},
                {"label":"Carbs","metric":{"kinds":["meal","drink"],"op":"sum","field":"carbs_g"},"goal":250,"unit":"g"},
                {"label":"Fat","metric":{"kinds":["meal","drink"],"op":"sum","field":"fat_g"},"goal":70,"unit":"g"}]}
            ]}
            """;

    private static final String SPENDING = """
            {"v":1,"title":"Spending","icon":"wallet","accent":"sunny","tone":"surface","badge":{"kind":"delta","block":0},"blocks":[
              {"type":"stat","label":"spent","metric":{"kinds":["expense"],"op":"sum","field":"amount"},"period":"range","goal":30000,"goal_period":"week","compare":"previous_period","better":"lower","unit":"JPY"},
              {"type":"breakdown","label":"by category","metric":{"kinds":["expense"],"op":"sum","field":"amount"},"period":"range","by":"category","top":3,"style":"segmented","unit":"JPY"}
            ]}
            """;

    private static final String GYM = """
            {"v":1,"title":"Gym","icon":"dumbbell","accent":"ink","tone":"ink","blocks":[
              {"type":"checkins","label":"visits","metric":{"kinds":["workout"],"op":"count"},"period":"range","goal":5,"goal_period":"week"},
              {"type":"latest","label":"Last session","kinds":["workout"],"fields":["type","minutes"]}
            ]}
            """;

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18")
                    .asCompatibleSubstituteFor("postgres"));
        }

        @Bean
        @Primary
        Clock fixedCardClock() {
            return Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneId.of("UTC"));
        }
    }
}
