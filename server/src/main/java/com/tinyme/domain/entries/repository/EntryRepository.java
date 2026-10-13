package com.tinyme.domain.entries.repository;

import com.tinyme.domain.entries.entity.EntryKindEntity;
import com.tinyme.domain.entries.model.add.NewEntry;
import com.tinyme.domain.entries.model.aggregate.TodayTotal;
import com.tinyme.domain.entries.model.aggregate.AggregateQuery;
import com.tinyme.domain.entries.model.aggregate.AggregateResult;
import com.tinyme.domain.entries.model.aggregate.AggregateMetric;
import com.tinyme.domain.entries.model.aggregate.Bucket;
import com.tinyme.domain.entries.model.aggregate.BreakdownQuery;
import com.tinyme.domain.entries.model.aggregate.BreakdownResult;
import com.tinyme.domain.entries.model.aggregate.BreakdownOther;
import com.tinyme.domain.entries.model.aggregate.BreakdownSlice;
import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.EntryDay;
import com.tinyme.domain.entries.model.RecentKind;
import com.tinyme.domain.entries.model.query.EntryQuery;
import com.tinyme.domain.entries.model.query.EntryQueryResult;
import com.tinyme.domain.entries.model.query.EntryFilters;
import com.tinyme.domain.entries.entity.EntryEntity;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;

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
    public boolean hasLiveDataField(String kind, String field) {
        var params = new MapSqlParameterSource().addValue("kind", kind).addValue("field", field);
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM entries
                 WHERE kind = :kind AND deleted_at IS NULL AND jsonb_exists(data, :field))
                """, params, Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Transactional(readOnly = true)
    public List<TodayTotal> todayTotals(LocalDate day) {
        Objects.requireNonNull(day, "day");
        return entries.todayTotals(day).stream()
                .map(row -> new TodayTotal(row.getKind(), row.getType(), row.getQuantity()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EntryDay> habitDays(List<String> kinds, LocalDate from, LocalDate to) {
        Objects.requireNonNull(kinds, "kinds");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (kinds.isEmpty()) return List.of();
        if (from.isAfter(to)) throw new IllegalArgumentException("from must be on or before to");
        return entries.habitDays(kinds, from, to).stream()
                .map(row -> new EntryDay(row.getKind(), row.getDay())).toList();
    }

    @Transactional(readOnly = true)
    public List<String> recentKinds(LocalDate from, LocalDate to, long minimumEntries) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) throw new IllegalArgumentException("from must be on or before to");
        if (minimumEntries < 1) throw new IllegalArgumentException("minimumEntries must be positive");
        return entries.recentKinds(from, to, minimumEntries).stream().map(RecentKindCount::getKind).toList();
    }

    @Transactional(readOnly = true)
    public List<RecentKind> recentCanonicalKinds(LocalDate from, LocalDate to, int limit) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) throw new IllegalArgumentException("from must be on or before to");
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be between 1 and 20");
        return entries.recentCanonicalKinds(from, to, org.springframework.data.domain.PageRequest.of(0, limit))
                .stream()
                .map(row -> new RecentKind(row.getKind(), row.getEntryCount(), row.getLastLoggedOn()))
                .toList();
    }

    @Transactional(readOnly = true)
    public EntryQueryResult find(EntryQuery query) {
        Objects.requireNonNull(query, "query");
        var filters = query.filters();
        var fetched = findRows(filters, query.limit() + 1, null, null, 280);
        boolean truncated = fetched.size() > query.limit();
        List<EntrySnapshot> visible = truncated
                ? new ArrayList<>(fetched.subList(0, query.limit()))
                : fetched;
        return new EntryQueryResult(true, visible, truncated);
    }

    @Transactional(readOnly = true)
    public EntryPageRows findPage(EntryFilters filters, int limit, EntryCursor cursor) {
        Objects.requireNonNull(filters, "filters");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        var fetched = findRows(filters, limit + 1, cursor == null ? null : cursor.ts(),
                cursor == null ? null : cursor.id(), null);
        boolean hasMore = fetched.size() > limit;
        List<EntrySnapshot> visible = hasMore
                ? new ArrayList<>(fetched.subList(0, limit))
                : fetched;
        return new EntryPageRows(visible, hasMore);
    }

    private List<EntrySnapshot> findRows(EntryFilters filters, int fetch,
                                         Instant cursorTs, UUID cursorId, Integer maxTextLength) {
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("fetch", fetch);
        String textExpression = maxTextLength == null ? "e.text" : "left(e.text, :maxText)";
        if (maxTextLength != null) parameters.addValue("maxText", maxTextLength);
        StringBuilder sql = new StringBuilder("SELECT e.id, e.ts, e.local_day, e.kind, e.quantity, ")
                .append(textExpression).append(" AS text, e.data::text AS data FROM entries e ")
                .append("WHERE e.deleted_at IS NULL");

        if (!filters.kinds().isEmpty()) {
            sql.append(" AND e.kind IN (:kinds)");
            parameters.addValue("kinds", filters.kinds());
        }
        if (filters.from() != null) {
            sql.append(" AND e.local_day >= :from");
            parameters.addValue("from", filters.from());
        }
        if (filters.to() != null) {
            sql.append(" AND e.local_day <= :to");
            parameters.addValue("to", filters.to());
        }
        if (!filters.where().isEmpty()) {
            sql.append(" AND e.data @> CAST(:where AS jsonb)");
            parameters.addValue("where", JSON.writeValueAsString(filters.where()));
        }
        if (filters.text() != null) {
            sql.append(" AND (e.search @@ websearch_to_tsquery('simple', :text) OR e.text % :text)");
            parameters.addValue("text", filters.text());
        }
        if (filters.numericField() != null) {
            sql.append(" AND jsonb_typeof(e.data -> CAST(:numericField AS text)) = 'number'");
            parameters.addValue("numericField", filters.numericField());
        }
        if (cursorTs != null) {
            sql.append(" AND (e.ts, e.id) < (:cursorTs, :cursorId)");
            parameters.addValue("cursorTs", OffsetDateTime.ofInstant(cursorTs, java.time.ZoneOffset.UTC));
            parameters.addValue("cursorId", cursorId);
        }
        sql.append(" ORDER BY e.ts DESC, e.id DESC LIMIT :fetch");

        return jdbc.query(sql.toString(), parameters, (result, row) ->
                new EntrySnapshot(result.getObject("id", UUID.class),
                        result.getObject("ts", OffsetDateTime.class).toInstant(),
                        result.getObject("local_day", LocalDate.class),
                        result.getString("kind"),
                        result.getObject("quantity", BigDecimal.class),
                        result.getString("text"),
                        JSON.readValue(result.getString("data"), DATA_TYPE)));
    }

    public record EntryCursor(Instant ts, UUID id) { }
    public record EntryPageRows(List<EntrySnapshot> entries, boolean hasMore) {
        public EntryPageRows { entries = List.copyOf(entries); }
    }

    @Transactional(readOnly = true)
    public AggregateResult aggregate(AggregateQuery query) {
        Objects.requireNonNull(query, "query");
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        String baseFilters = filters(query.kinds(), query.allKinds(), query.from(), query.to(), query.where(),
                query.metric(), query.field(), "e", parameters);

        List<Bucket> buckets = List.of();
        BigDecimal value = null;
        Long rowCount = null;
        if (!query.groupBy().grouped()) {
            String sql = "SELECT " + query.metric().sqlExpression("e") + " AS value, COUNT(*) AS entries "
                    + "FROM entries e WHERE " + baseFilters;
            var result = jdbc.queryForMap(sql, parameters);
            value = (BigDecimal) result.get("value");
            rowCount = ((Number) result.get("entries")).longValue();
        } else if (query.from() != null && query.to() != null) {
            parameters.addValue("unit", query.groupBy().unit());
            parameters.addValue("step", query.groupBy().stepInterval());
            String sql = "WITH periods AS ("
                    + "SELECT generate_series("
                    + "date_trunc(CAST(:unit AS text), CAST(:from AS timestamp)), "
                    + "date_trunc(CAST(:unit AS text), CAST(:to AS timestamp)), "
                    + "CAST(:step AS interval))::date AS period) "
                    + "SELECT p.period, " + query.metric().sqlExpression("e") + " AS value, "
                    + "COUNT(e.id) AS entries FROM periods p LEFT JOIN entries e ON "
                    + "date_trunc(CAST(:unit AS text), e.local_day::timestamp)::date = p.period "
                    + "AND " + baseFilters + " GROUP BY p.period ORDER BY p.period";
            buckets = this.query(sql, parameters);
        } else {
            parameters.addValue("unit", query.groupBy().unit());
            String sql = "SELECT date_trunc(CAST(:unit AS text), e.local_day::timestamp)::date AS period, "
                    + query.metric().sqlExpression("e") + " AS value, COUNT(e.id) AS entries "
                    + "FROM entries e WHERE " + baseFilters
                    + " GROUP BY period ORDER BY period LIMIT " + MAX_UNBOUNDED_BUCKETS_QUERY;
            buckets = this.query(sql, parameters);
            if (buckets.size() > AggregateQuery.MAX_BUCKETS) {
                throw new IllegalArgumentException("query may produce at most "
                        + AggregateQuery.MAX_BUCKETS + " buckets");
            }
        }

        String resultField = query.metric().requiresField() ? query.field() : null;
        return new AggregateResult(query.kinds(), List.of(), query.metric(),
                resultField, query.where(), query.from(), query.to(), query.groupBy(), value, rowCount, buckets);
    }

    @Transactional(readOnly = true)
    public BreakdownResult breakdown(BreakdownQuery query) {
        Objects.requireNonNull(query, "query");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("by", query.by());
        String base = filters(query.kinds(), query.allKinds(), query.from(), query.to(), query.where(),
                query.metric(), query.field(), "e", params);
        String expression = query.metric().sqlExpression("e");
        String sql = "SELECT category_key, value, SUM(value) OVER () AS total FROM ("
                + "SELECT e.data ->> CAST(:by AS text) AS category_key, " + expression + " AS value "
                + "FROM entries e WHERE " + base + " GROUP BY 1) g "
                + "ORDER BY value DESC NULLS LAST LIMIT :top";
        params.addValue("top", query.top());
        List<BreakdownSlice> raw = new ArrayList<>();
        BigDecimal[] resultTotal = {BigDecimal.ZERO};
        jdbc.query(sql, params, rs -> {
            BigDecimal sliceValue = rs.getObject("value", BigDecimal.class);
            resultTotal[0] = rs.getObject("total", BigDecimal.class);
            raw.add(new BreakdownSlice(rs.getString("category_key"), sliceValue, null));
        });
        BigDecimal total = resultTotal[0] == null ? BigDecimal.ZERO : resultTotal[0];
        BigDecimal topTotal = raw.stream().map(BreakdownSlice::value).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BreakdownSlice> slices = raw.stream().map(slice -> new BreakdownSlice(slice.key(), slice.value(),
                percentage(slice.value(), total))).toList();
        BigDecimal other = total.subtract(topTotal);
        return new BreakdownResult(slices, new BreakdownOther(other, percentage(other, total)), total);
    }

    private static BigDecimal percentage(BigDecimal value, BigDecimal total) {
        if (value == null || total == null || total.signum() == 0) return BigDecimal.ZERO;
        return value.multiply(BigDecimal.valueOf(100)).divide(total, 2, java.math.RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public Optional<EntrySnapshot> findLatest(List<String> kinds) {
        Objects.requireNonNull(kinds, "kinds");
        MapSqlParameterSource params = new MapSqlParameterSource();
        String filter = "deleted_at IS NULL";
        if (!kinds.equals(List.of("*"))) {
            filter += " AND kind IN (:kinds)";
            params.addValue("kinds", kinds.stream().distinct().toList());
        }
        String sql = "SELECT id, ts, local_day, kind, quantity, text, data::text AS data FROM entries WHERE "
                + filter + " ORDER BY ts DESC, id DESC LIMIT 1";
        return jdbc.query(sql, params, (rs, row) -> new EntrySnapshot(rs.getObject("id", UUID.class),
                rs.getObject("ts", OffsetDateTime.class).toInstant(), rs.getObject("local_day", LocalDate.class),
                rs.getString("kind"), rs.getObject("quantity", BigDecimal.class), rs.getString("text"),
                JSON.readValue(rs.getString("data"), DATA_TYPE))).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Map<LocalDate, String> activityTypesByDay(List<String> kinds, Map<String, Object> where,
                                                     LocalDate from, LocalDate to) {
        Objects.requireNonNull(kinds, "kinds");
        Objects.requireNonNull(where, "where");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("from", from).addValue("to", to);
        String filter = "deleted_at IS NULL AND local_day BETWEEN :from AND :to "
                + "AND jsonb_typeof(data -> 'type') = 'string' "
                + "AND nullif(btrim(data ->> 'type'), '') IS NOT NULL";
        if (!kinds.equals(List.of("*"))) {
            filter += " AND kind IN (:kinds)";
            params.addValue("kinds", kinds.stream().distinct().toList());
        }
        if (!where.isEmpty()) {
            filter += " AND data @> CAST(:where AS jsonb)";
            params.addValue("where", JSON.writeValueAsString(where));
        }
        String type = "nullif(btrim(data ->> 'type'), '')";
        String sql = "SELECT local_day, string_agg(DISTINCT " + type + ", ', ' ORDER BY " + type
                + ") AS activity FROM entries WHERE " + filter + " GROUP BY local_day";
        Map<LocalDate, String> result = new LinkedHashMap<>();
        jdbc.query(sql, params, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                result.put(rs.getObject("local_day", LocalDate.class), rs.getString("activity")));
        return Map.copyOf(result);
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

    private static String filters(List<String> kinds, boolean allKinds, LocalDate from, LocalDate to,
                                  Map<String, Object> where, AggregateMetric metric, String field,
                                  String alias, MapSqlParameterSource parameters) {
        StringBuilder sql = new StringBuilder(alias).append(".deleted_at IS NULL");
        if (!allKinds) {
            sql.append(" AND ").append(alias).append(".kind IN (:kinds)");
            parameters.addValue("kinds", kinds);
        }
        if (from != null) {
            sql.append(" AND ").append(alias).append(".local_day >= :from");
            parameters.addValue("from", from);
        }
        if (to != null) {
            sql.append(" AND ").append(alias).append(".local_day <= :to");
            parameters.addValue("to", to);
        }
        if (!where.isEmpty()) {
            sql.append(" AND ").append(alias).append(".data @> CAST(:where AS jsonb)");
            parameters.addValue("where", JSON.writeValueAsString(where));
        }
        if (metric.requiresField()) {
            sql.append(metric.sqlFieldFilter(alias));
            parameters.addValue("field", field);
        }
        return sql.toString();
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
