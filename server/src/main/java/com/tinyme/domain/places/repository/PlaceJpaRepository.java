package com.tinyme.domain.places.repository;

import com.tinyme.domain.places.entity.PlaceEntity;
import com.tinyme.domain.places.model.PlaceSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PlaceJpaRepository extends JpaRepository<PlaceEntity, UUID> {
    Optional<PlaceEntity> findByIdAndDeletedAtIsNull(UUID id);
    Optional<PlaceEntity> findByProviderPlaceIdAndDeletedAtIsNull(String providerPlaceId);
    List<PlaceEntity> findAllByDeletedAtIsNull();
    List<PlaceEntity> findAllByDeletedAtIsNullOrderByCreatedAtDescIdDesc();
    List<PlaceEntity> findAllByDeletedAtIsNullAndVisitedAtIsNullOrderByCreatedAtDescIdDesc();
}
