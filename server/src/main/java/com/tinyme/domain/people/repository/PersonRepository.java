package com.tinyme.domain.people.repository;

import com.tinyme.domain.people.entity.PersonEntity;
import com.tinyme.domain.people.model.PersonSnapshot;
import com.tinyme.domain.people.model.get.PeopleLookup;
import com.tinyme.domain.people.model.get.PersonCandidate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PersonRepository {
    private final PersonJpaRepository people;
    private final NamedParameterJdbcTemplate jdbc;

    PersonRepository(PersonJpaRepository people, NamedParameterJdbcTemplate jdbc) {
        this.people = Objects.requireNonNull(people, "people");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Transactional(readOnly = true)
    public Optional<PersonSnapshot> findBySlug(String slug) {
        return people.findBySlugAndDeletedAtIsNull(slug).map(PersonRepository::snapshot);
    }

    @Transactional(readOnly = true)
    public boolean existsBySlug(String slug) {
        return people.existsBySlug(slug);
    }

    @Transactional(readOnly = true)
    public List<PersonCandidate> findExact(String query, String slug) {
        Objects.requireNonNull(query, "query");
        String sql = """
                SELECT p.id, p.slug, p.display_name, p.aliases, p.relationship, p.memory_path
                FROM people p
                WHERE p.deleted_at IS NULL
                  AND (p.slug = :slug
                    OR lower(p.display_name) = lower(:query)
                    OR EXISTS (
                        SELECT 1 FROM unnest(p.aliases) AS alias_name
                        WHERE lower(alias_name) = lower(:query)))
                ORDER BY p.display_name ASC, p.slug ASC
                LIMIT :limit
                """;
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("query", query)
                .addValue("slug", slug)
                .addValue("limit", PeopleLookup.MAX_MATCHES), PersonRepository::candidate);
    }

    @Transactional(readOnly = true)
    public List<PersonCandidate> findFuzzy(String query, int limit) {
        Objects.requireNonNull(query, "query");
        int boundedLimit = Math.clamp(limit, 1, PeopleLookup.MAX_MATCHES);
        String sql = """
                SELECT p.id, p.slug, p.display_name, p.aliases, p.relationship, p.memory_path,
                       GREATEST(similarity(p.display_name, :query), alias_scores.score) AS score
                FROM people p
                CROSS JOIN LATERAL (
                    SELECT COALESCE(MAX(similarity(a.alias, :query)), 0) AS score
                    FROM unnest(p.aliases) AS a(alias)
                ) AS alias_scores
                WHERE p.deleted_at IS NULL
                  AND GREATEST(similarity(p.display_name, :query), alias_scores.score) > 0.3
                ORDER BY score DESC, p.display_name ASC, p.slug ASC
                LIMIT :limit
                """;
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("query", query)
                .addValue("limit", boundedLimit), PersonRepository::candidate);
    }

    @Transactional(readOnly = true)
    public Map<UUID, Instant> lastSeen(List<UUID> personIds) {
        if (personIds.isEmpty()) return Map.of();
        String sql = """
                SELECT ep.person_id, MAX(e.ts) AS last_seen
                FROM entry_people ep
                JOIN entries e ON e.id = ep.entry_id AND e.deleted_at IS NULL
                WHERE ep.person_id IN (:personIds)
                GROUP BY ep.person_id
                """;
        Map<UUID, Instant> lastSeen = new LinkedHashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("personIds", personIds), result -> {
            OffsetDateTime timestamp = result.getObject("last_seen", OffsetDateTime.class);
            lastSeen.put(result.getObject("person_id", UUID.class), timestamp.toInstant());
        });
        return Map.copyOf(lastSeen);
    }

    private static PersonCandidate candidate(ResultSet result, int row) throws SQLException {
        Array aliases = result.getArray("aliases");
        String[] values = aliases == null ? new String[0] : (String[]) aliases.getArray();
        PersonSnapshot person = new PersonSnapshot(
                result.getString("slug"),
                result.getString("display_name"),
                cleanAliases(values),
                result.getString("relationship"),
                result.getString("memory_path"));
        return new PersonCandidate(result.getObject("id", UUID.class), person);
    }

    private static PersonSnapshot snapshot(PersonEntity person) {
        return new PersonSnapshot(person.getSlug(), person.getDisplayName(),
                cleanAliases(person.getAliases()), person.getRelationship(), person.getMemoryPath());
    }

    private static List<String> cleanAliases(String[] aliases) {
        return Arrays.stream(aliases).filter(Objects::nonNull).toList();
    }
}
