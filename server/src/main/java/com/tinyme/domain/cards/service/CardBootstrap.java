package com.tinyme.domain.cards.service;

import com.tinyme.domain.cards.entity.CardEntity;
import com.tinyme.domain.cards.model.CardSpecParser;
import com.tinyme.domain.cards.model.CardSpecWriter;
import com.tinyme.domain.cards.model.CardStatus;
import com.tinyme.domain.cards.repository.CardRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

@Component
@Order(-2)
@DependsOnDatabaseInitialization
public class CardBootstrap implements ApplicationRunner {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> SPEC_TYPE = new TypeReference<>() {};
    private static final String JOURNAL_SLUG = "journal";

    private final CardRepository cards;
    private final CardSpecParser parser;
    private final CardSpecWriter writer;
    private final Resource journalSeed;

    public CardBootstrap(CardRepository cards, CardSpecParser parser, CardSpecWriter writer,
                         @Value("classpath:seed/cards/journal.json") Resource journalSeed) {
        this.cards = cards;
        this.parser = parser;
        this.writer = writer;
        this.journalSeed = journalSeed;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        JsonNode seed;
        try (var input = journalSeed.getInputStream()) {
            seed = JSON.readTree(input);
        }
        var spec = parser.parseTrusted(seed);
        Map<String, Object> storedSpec = JSON.convertValue(writer.write(spec), SPEC_TYPE);
        var existing = cards.findBySlug(JOURNAL_SLUG);
        if (existing.isEmpty()) {
            cards.save(new CardEntity(JOURNAL_SLUG, spec.title(), storedSpec,
                    CardStatus.ACTIVE, true, 0, Instant.now()));
            return;
        }

        var journal = existing.get();
        JsonNode storedCanonical = writer.write(parser.parseTrusted(JSON.valueToTree(journal.getSpec())));
        if (!storedCanonical.equals(writer.write(spec))) {
            journal.syncSystemSpec(spec.title(), storedSpec);
            cards.save(journal);
        }
    }
}
