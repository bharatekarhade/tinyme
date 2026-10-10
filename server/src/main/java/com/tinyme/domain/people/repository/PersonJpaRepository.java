package com.tinyme.domain.people.repository;

import com.tinyme.domain.people.entity.PersonEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PersonJpaRepository extends JpaRepository<PersonEntity, UUID> {
    Optional<PersonEntity> findBySlugAndDeletedAtIsNull(String slug);

    boolean existsBySlug(String slug);

    List<PersonEntity> findAllByDisplayNameIgnoreCaseAndDeletedAtIsNullOrderBySlugAsc(String displayName);

    @Query("select person.slug from PersonEntity person " +
            "where person.slug = :base or person.slug like concat(:base, '-%')")
    List<String> findTakenSlugs(@Param("base") String base);
}
