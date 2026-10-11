package com.tinyme;

import com.tinyme.domain.cards.entity.CardEntity;
import com.tinyme.domain.cards.model.CardSpecParser;
import com.tinyme.domain.cards.model.CardSpecWriter;
import com.tinyme.domain.cards.model.CardStatus;
import com.tinyme.domain.cards.repository.CardRepository;
import com.tinyme.domain.cards.service.CardBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "tinyme.agent.setup-enabled=false",
        "TINYME_OWNER_EMAIL=owner@test.tinyme.local",
        "TINYME_OWNER_PASSWORD=test-only-owner-password"
})
@Import(TinymeApplicationTests.DatabaseConfiguration.class)
class CardBootstrapTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Autowired JdbcTemplate jdbc;
    @Autowired CardRepository cards;
    @Autowired CardBootstrap bootstrap;
    @Autowired CardSpecParser parser;
    @Autowired CardSpecWriter writer;

    private static final String TEST_SLUG_PREFIX = "cards-test-" + UUID.randomUUID() + "-";

    @BeforeEach
    void cleanTestCards() {
        jdbc.update("DELETE FROM cards WHERE slug LIKE ?", TEST_SLUG_PREFIX + "%");
    }

    @Test
    void journalIsSeededOnceAndListedFirst() throws Exception {
        bootstrap.run(new DefaultApplicationArguments(new String[0]));
        var first = cards.findBySlug("journal").orElseThrow();
        Instant originalUpdatedAt = first.getUpdatedAt();
        bootstrap.run(new DefaultApplicationArguments(new String[0]));
        var second = cards.findBySlug("journal").orElseThrow();

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(second.getTitle()).isEqualTo("Journal");
        assertThat(second.getStatus()).isEqualTo(CardStatus.ACTIVE);
        assertThat(second.isSystem()).isTrue();
        assertThat(second.getPosition()).isZero();
        assertThat(second.getSpec()).containsEntry("v", 1);
        assertThat(cards.activeCards()).extracting(CardEntity::getSlug).containsExactly("journal");
        assertThat(cards.nextActivePosition()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cards WHERE slug = 'journal' AND deleted_at IS NULL", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void changedJournalSeedUpdatesSpecAndTitleButKeepsIdentityAndPlacement() throws Exception {
        var original = cards.findBySlug("journal").orElseThrow();
        int position = original.getPosition();
        Instant activatedAt = original.getActivatedAt();
        UUID id = original.getId();
        String changedSeed = journalJson("My Journal", "logged entries");
        var changedBootstrap = new CardBootstrap(cards, parser, writer,
                new ByteArrayResource(changedSeed.getBytes(StandardCharsets.UTF_8)));

        changedBootstrap.run(new DefaultApplicationArguments(new String[0]));

        var changed = cards.findBySlug("journal").orElseThrow();
        assertThat(changed.getId()).isEqualTo(id);
        assertThat(changed.getTitle()).isEqualTo("My Journal");
        assertThat(changed.getSpec()).containsEntry("title", "My Journal");
        assertThat(changed.getSpec().toString()).contains("logged entries");
        assertThat(changed.getPosition()).isEqualTo(position);
        assertThat(changed.getActivatedAt()).isEqualTo(activatedAt);
    }

    @Test
    void savesDraftAndReadsItsSpecBack() {
        String slug = TEST_SLUG_PREFIX + "roundtrip";
        Map<String, Object> spec = ordinarySpec("Round trip");
        cards.save(new CardEntity(slug, "Round trip", spec, CardStatus.DRAFT, false, 0, null));

        assertThat(cards.findBySlug(slug).orElseThrow().getSpec()).isEqualTo(spec);
    }

    @Test
    void secondLiveCardWithSameSlugViolatesUniqueIndex() {
        String slug = TEST_SLUG_PREFIX + "duplicate";
        cards.save(new CardEntity(slug, "First", ordinarySpec("First"), CardStatus.DRAFT, false, 0, null));

        assertThatThrownBy(() -> cards.save(new CardEntity(slug, "Second", ordinarySpec("Second"),
                CardStatus.DRAFT, false, 0, null))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletedCardFreesItsSlug() {
        String slug = TEST_SLUG_PREFIX + "reusable";
        var deleted = cards.save(new CardEntity(slug, "Old", ordinarySpec("Old"), CardStatus.DRAFT, false, 0, null));
        UUID deletedId = deleted.getId();
        deleted.softDelete(Instant.now());
        cards.save(deleted);
        var replacement = cards.save(new CardEntity(slug, "New", ordinarySpec("New"), CardStatus.DRAFT, false, 0, null));

        assertThat(replacement.getId()).isNotEqualTo(deletedId);
        assertThat(cards.findBySlug(slug).orElseThrow().getId()).isEqualTo(replacement.getId());
    }

    @Test
    void activeCardsExcludeDraftsAndDeletedCards() {
        String activeSlug = TEST_SLUG_PREFIX + "active";
        String deletedSlug = TEST_SLUG_PREFIX + "deleted";
        cards.save(new CardEntity(TEST_SLUG_PREFIX + "draft", "Draft", ordinarySpec("Draft"), CardStatus.DRAFT, false, 1, null));
        cards.save(new CardEntity(activeSlug, "Active", ordinarySpec("Active"), CardStatus.ACTIVE, false, 1, Instant.now()));
        var deleted = cards.save(new CardEntity(deletedSlug, "Deleted", ordinarySpec("Deleted"),
                CardStatus.ACTIVE, false, 2, Instant.now()));
        deleted.softDelete(Instant.now());
        cards.save(deleted);

        assertThat(cards.activeCards()).extracting(CardEntity::getSlug).containsExactly("journal", activeSlug);
    }

    @Test
    void updateChangesUpdatedAt() throws InterruptedException {
        var card = cards.save(new CardEntity(TEST_SLUG_PREFIX + "updated", "Before", ordinarySpec("Before"),
                CardStatus.DRAFT, false, 0, null));
        Instant before = card.getUpdatedAt();
        Thread.sleep(10);
        card.replaceSpec("After", ordinarySpec("After"));
        var updated = cards.save(card);

        assertThat(updated.getUpdatedAt()).isAfter(before);
    }

    @Test
    void databaseRejectsActiveCardWithoutActivationTime() {
        String slug = TEST_SLUG_PREFIX + "invalid-active";
        String spec = JSON.writeValueAsString(ordinarySpec("Invalid"));

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO cards (slug, title, spec, status, system, position)
                VALUES (?, 'Invalid', CAST(? AS jsonb), 'active', false, 0)
                """, slug, spec)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNegativePosition() {
        String slug = TEST_SLUG_PREFIX + "negative-position";
        String spec = JSON.writeValueAsString(ordinarySpec("Invalid"));

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO cards (slug, title, spec, status, system, position)
                VALUES (?, 'Invalid', CAST(? AS jsonb), 'draft', false, -1)
                """, slug, spec)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Map<String, Object> ordinarySpec(String title) {
        JsonNode json = JSON.readTree("""
                {"v":1,"title":"%s","icon":"star","accent":"mint","tone":"surface","blocks":[
                  {"type":"stat","label":"entries","metric":{"kinds":["drink"],"op":"count"},"period":"today"}
                ]}
                """.formatted(title));
        return JSON.convertValue(writer.write(parser.parse(json)), MAP_TYPE);
    }

    private static String journalJson(String title, String label) {
        return """
                {"v":1,"title":"%s","icon":"journal","accent":"mint","tone":"brand",
                 "badge":{"kind":"streak","block":0},"blocks":[
                  {"type":"stat","label":"%s","metric":{"kinds":["*"],"op":"count"},"period":"range","compare":"previous_period","better":"higher"},
                  {"type":"chart","label":"per day","metric":{"kinds":["*"],"op":"count"},"period":"range","bucket":"auto","style":"bars","highlight":"peak"}
                 ]}
                """.formatted(title, label);
    }
}
