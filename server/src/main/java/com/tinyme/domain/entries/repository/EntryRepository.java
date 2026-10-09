package com.tinyme.domain.entries.repository;

import com.tinyme.domain.entries.entity.EntryKindEntity;
import com.tinyme.domain.entries.model.add.NewEntry;
import com.tinyme.domain.entries.model.aggregate.TodayTotal;
import com.tinyme.domain.entries.model.aggregate.AggregateQuery;
import com.tinyme.domain.entries.model.aggregate.AggregateResult;
import com.tinyme.domain.entries.model.aggregate.Bucket;
import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.query.EntryQuery;
import com.tinyme.domain.entries.model.query.EntryQueryResult;
import com.tinyme.domain.entries.entity.EntryEntity;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;

import javax.swing.text.html.Option;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class EntryRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> DATA_TYPE = new TypeReference<>() {};
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
    public EntryQueryResult find(EntryQuery query) {
        Objects.requireNonNull(query, "query");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("fetch", query.limit() + 1);
        StringBuilder sql = new StringBuilder("SELECT e.id, e.ts, e.local_day, e.kind, e.quantity, ")
                .append("left(e.text, 280) AS text, e.data::text AS data FROM entries e ")
                .append("WHERE e.deleted_at IS NULL");

        if (query.kind() != null) {
            sql.append(" AND e.kind = :kind");
            parameters.addValue("kind", query.kind());
        }
        if (query.from() != null) {
            sql.append(" AND e.local_day >= :from");
            parameters.addValue("from", query.from());
        }
        if (query.to() != null) {
            sql.append(" AND e.local_day <= :to");
            parameters.addValue("to", query.to());
        }
        if (!query.where().isEmpty()) {
            sql.append(" AND e.data @> CAST(:where AS jsonb)");
            parameters.addValue("where", JSON.writeValueAsString(query.where()));
        }
        if (query.text() != null) {
            sql.append(" AND (e.search @@ websearch_to_tsquery('simple', :text) OR e.text % :text)");
            parameters.addValue("text", query.text());
        }
        sql.append(" ORDER BY e.ts DESC, e.id DESC LIMIT :fetch");

        List<EntrySnapshot> fetched = jdbc.query(sql.toString(), parameters, (result, row) ->
                new EntrySnapshot(result.getObject("id", UUID.class),
                        result.getObject("ts", OffsetDateTime.class).toInstant(),
                        result.getObject("local_day", LocalDate.class),
                        result.getString("kind"),
                        result.getObject("quantity", BigDecimal.class),
                        result.getString("text"),
                        JSON.readValue(result.getString("data"), DATA_TYPE)));
        boolean truncated = fetched.size() > query.limit();
        List<EntrySnapshot> visible = truncated
                ? new ArrayList<>(fetched.subList(0, query.limit()))
                : fetched;
        return new EntryQueryResult(true, visible, truncated);
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

    @Transactional(readOnly = true)
    public Optional<EntrySnapshot> findLive(UUID id) {
        Objects.requireNonNull(id, "id");
        return entries.findByIdAndDeletedAtIsNull(id)
                .map(EntryRepository::toSnapshot);
    }

    @Transactional
    public EntrySnapshot update(
            UUID id,
            String kind,
            BigDecimal quantity,
            String text,
            Map<String, Object> data,
            Instant ts,
            LocalDate localDay
    ){
        Objects.requireNonNull(id, "id");
        EntryEntity entry = entries.findByIdAndDeletedAtIsNull(id).orElseThrow();
        EntryKindEntity entryKind = kinds.findById(kind).orElseThrow();
        entry.setKind(entryKind);
        entry.setData(data);
        entry.setQuantity(quantity);
        entry.setText(text);
        entry.setLocalDay(localDay);
        entry.setTs(ts);
        return toSnapshot(entry);
    }

    @Transactional
    public Optional<EntrySnapshot> softDelete(UUID id, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        Optional<EntryEntity> found = entries.findLockedById(id);
        if (found.isEmpty()) return Optional.empty();

        EntryEntity entry = found.get();
        if (entry.getDeletedAt() == null) {
            entry.setDeletedAt(now);
        }
        return Optional.of(toSnapshot(entry));
    }

    private static EntrySnapshot toSnapshot(EntryEntity entry) {
        Objects.requireNonNull(entry, "entry");
        return new EntrySnapshot(
                entry.getId(),
                entry.getTs(),
                entry.getLocalDay(),
                entry.getKind().getKind(),
                entry.getQuantity(),
                entry.getText(),
                entry.getData());
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
