package com.tinyme.domain.people.repository;

import com.tinyme.domain.people.entity.PersonEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface PersonJpaRepository extends JpaRepository<PersonEntity, UUID> {
    Optional<PersonEntity> findBySlugAndDeletedAtIsNull(String slug);

    boolean existsBySlug(String slug);
}
