package com.tinyme.domain.entries.repository;

import com.tinyme.domain.entries.model.NewEntry;
import com.tinyme.domain.entries.model.TodayTotal;
import com.tinyme.domain.entries.model.AggregateQuery;
import com.tinyme.domain.entries.model.AggregateResult;
import com.tinyme.domain.entries.model.Bucket;
import com.tinyme.domain.entries.entity.EntryEntity;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.sql.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class EntryRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_UNBOUNDED_BUCKETS_QUERY = AggregateQuery.MAX_BUCKETS + 1;

    private final EntryJpaRepository entries;
    private final EntryKindJpaRepository kinds;
    private final NamedParameterJdbcTemplate jdbc;

    EntryRepository(EntryJpaRepository entries,
                    EntryKindJpaRepository kinds,
                    NamedParameterJdbcTemplate jdbc) {
        this.entries = Objects.requireNonNull(entries, "entries");
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Transactional
    public UUID insert(NewEntry entry) {
        Objects.requireNonNull(entry, "entry");

        EntryEntity entity = new EntryEntity(
                entry.ts(),
                entry.localDay(),
                kinds.getReferenceById(entry.kind()));
        entity.setQuantity(entry.quantity());
        entity.setText(entry.text());
        entity.setData(entry.data());
        entity.setTags(entry.tags().toArray(String[]::new));
        entity.setSource(entry.source());

        return entries.save(entity).getId();
    }

    @Transactional(readOnly = true)
    public BigDecimal totalForDay(String kind,
                                  LocalDate day,
                                  String typeOrNull) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(day, "day");
        return entries.totalForDay(kind, day, typeOrNull);
    }

    @Transactional(readOnly = true)
    public List<TodayTotal> todayTotals(LocalDate day) {
        Objects.requireNonNull(day, "day");
        return entries.todayTotals(day).stream()
                .map(row -> new TodayTotal(row.getKind(), row.getType(), row.getQuantity()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AggregateResult aggregate(AggregateQuery query) {
        Objects.requireNonNull(query, "query");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("kind", query.kind());
        String whereFilter = whereFilter(query, parameters, "e");
        String fieldFilter = query.metric().sqlFieldFilter("e");
        if (query.metric().requiresField()) parameters.addValue("field", query.field());
        if (query.from() != null) parameters.addValue("from", query.from());
        if (query.to() != null) parameters.addValue("to", query.to());

        List<Bucket> buckets = List.of();
        BigDecimal value = null;
        Long rowCount = null;
        if (!query.groupBy().grouped()) {
            String sql = "SELECT " + query.metric().sqlExpression("e") + " AS value, COUNT(*) AS entries "
                    + "FROM entries e WHERE " + filters(query, "e", whereFilter, fieldFilter);
            var result = jdbc.queryForMap(sql, parameters);
            value = (BigDecimal) result.get("value");
            rowCount = ((Number) result.get("entries")).longValue();
        } else if (query.from() != null && query.to() != null) {
            parameters.addValue("unit", query.groupBy().unit());
            parameters.addValue("step", query.groupBy().stepInterval());
            String joinFilters = "e.kind = :kind AND e.deleted_at IS NULL "
                    + "AND e.local_day BETWEEN :from AND :to" + whereFilter + fieldFilter;
            String sql = "WITH periods AS ("
                    + "SELECT generate_series("
                    + "date_trunc(CAST(:unit AS text), CAST(:from AS timestamp)), "
                    + "date_trunc(CAST(:unit AS text), CAST(:to AS timestamp)), "
                    + "CAST(:step AS interval))::date AS period) "
                    + "SELECT p.period, " + query.metric().sqlExpression("e") + " AS value, "
                    + "COUNT(e.id) AS entries FROM periods p LEFT JOIN entries e ON "
                    + "date_trunc(CAST(:unit AS text), e.local_day::timestamp)::date = p.period "
                    + "AND " + joinFilters + " GROUP BY p.period ORDER BY p.period";
            buckets = this.query(sql, parameters);
        } else {
            parameters.addValue("unit", query.groupBy().unit());
            String sql = "SELECT date_trunc(CAST(:unit AS text), e.local_day::timestamp)::date AS period, "
                    + query.metric().sqlExpression("e") + " AS value, COUNT(e.id) AS entries "
                    + "FROM entries e WHERE " + filters(query, "e", whereFilter, fieldFilter)
                    + " GROUP BY period ORDER BY period LIMIT " + MAX_UNBOUNDED_BUCKETS_QUERY;
            buckets = this.query(sql, parameters);
            if (buckets.size() > AggregateQuery.MAX_BUCKETS) {
                throw new IllegalArgumentException("query may produce at most "
                        + AggregateQuery.MAX_BUCKETS + " buckets");
            }
        }

        String resultField = query.metric().requiresField() ? query.field() : null;
        return new AggregateResult(query.kind(), true, query.metric(), resultField, query.where(),
                query.from(), query.to(), query.groupBy(), value, rowCount, buckets);
    }

    private String whereFilter(AggregateQuery query, MapSqlParameterSource parameters, String alias) {
        if (query.where().isEmpty()) return "";
        parameters.addValue("where", JSON.writeValueAsString(query.where()));
        return " AND " + alias + ".data @> CAST(:where AS jsonb)";
    }

    private static String filters(AggregateQuery query, String alias,
                                  String whereFilter, String fieldFilter) {
        StringBuilder sql = new StringBuilder(alias).append(".kind = :kind AND ")
                .append(alias).append(".deleted_at IS NULL");
        if (query.from() != null) sql.append(" AND ").append(alias).append(".local_day >= :from");
        if (query.to() != null) sql.append(" AND ").append(alias).append(".local_day <= :to");
        return sql.append(whereFilter).append(fieldFilter).toString();
    }

    private List<Bucket> query(String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, (result, row) -> {
            Date periodDate = result.getDate("period");
            BigDecimal value = result.getObject("value", BigDecimal.class);
            long matchedEntries = result.getLong("entries");
            return new Bucket(periodDate.toLocalDate(), value, matchedEntries);
        });
    }
}
