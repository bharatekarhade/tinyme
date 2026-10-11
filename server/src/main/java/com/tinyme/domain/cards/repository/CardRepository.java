package com.tinyme.domain.cards.repository;

import com.tinyme.domain.cards.entity.CardEntity;
import com.tinyme.domain.cards.model.CardStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CardRepository {
    private final CardJpaRepository cards;

    CardRepository(CardJpaRepository cards) {
        this.cards = Objects.requireNonNull(cards, "cards");
    }

    @Transactional(readOnly = true)
    public Optional<CardEntity> findById(UUID id) {
        return cards.findByIdAndDeletedAtIsNull(Objects.requireNonNull(id, "id"));
    }

    @Transactional(readOnly = true)
    public Optional<CardEntity> findBySlug(String slug) {
        return cards.findBySlugAndDeletedAtIsNull(Objects.requireNonNull(slug, "slug"));
    }

    @Transactional(readOnly = true)
    public List<CardEntity> activeCards() {
        return cards.findByStatusAndDeletedAtIsNullOrderBySystemDescPositionAscCreatedAtAsc(CardStatus.ACTIVE);
    }

    /** Position to assign when a draft is activated. */
    @Transactional(readOnly = true)
    public int nextActivePosition() {
        return cards.nextActivePosition(CardStatus.ACTIVE);
    }

    @Transactional
    public CardEntity save(CardEntity card) {
        return cards.save(Objects.requireNonNull(card, "card"));
    }
}
