package com.tinyme.domain.cards.repository;

import com.tinyme.domain.cards.entity.CardEntity;
import com.tinyme.domain.cards.model.CardStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CardJpaRepository extends JpaRepository<CardEntity, UUID> {
    Optional<CardEntity> findByIdAndDeletedAtIsNull(UUID id);

    Optional<CardEntity> findBySlugAndDeletedAtIsNull(String slug);

    List<CardEntity> findByStatusAndDeletedAtIsNullOrderBySystemDescPositionAscCreatedAtAsc(CardStatus status);

    @Query("select coalesce(max(card.position), -1) + 1 from CardEntity card where card.status = :status and card.deletedAt is null")
    int nextActivePosition(CardStatus status);
}
