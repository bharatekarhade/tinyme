package com.tinyme.api.cards;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tinyme.domain.cards.entity.CardEntity;
import com.tinyme.domain.cards.model.Block;
import com.tinyme.domain.cards.model.BlockScope;
import com.tinyme.domain.cards.model.BlockTypes;
import com.tinyme.domain.cards.model.BreakdownBlock;
import com.tinyme.domain.cards.model.CardSpec;
import com.tinyme.domain.cards.model.CardSpecParser;
import com.tinyme.domain.cards.model.ChartBlock;
import com.tinyme.domain.cards.model.CheckinsBlock;
import com.tinyme.domain.cards.model.GoalItem;
import com.tinyme.domain.cards.model.GoalsBlock;
import com.tinyme.domain.cards.model.LatestBlock;
import com.tinyme.domain.cards.model.MetricBlock;
import com.tinyme.domain.cards.model.MetricSpec;
import com.tinyme.domain.cards.model.ProgressBlock;
import com.tinyme.domain.cards.model.StatBlock;
import com.tinyme.domain.cards.repository.CardRepository;
import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.aggregate.AggregateMetric;
import com.tinyme.domain.entries.model.aggregate.AggregateQuery;
import com.tinyme.domain.entries.model.aggregate.AggregateResult;
import com.tinyme.domain.entries.model.aggregate.DateRange;
import com.tinyme.domain.entries.model.aggregate.GroupBy;
import com.tinyme.domain.entries.model.aggregate.Period;
import com.tinyme.domain.entries.model.aggregate.Range;
import com.tinyme.domain.entries.model.query.EntryFilters;
import com.tinyme.domain.entries.model.query.EntryPage;
import com.tinyme.domain.entries.service.EntryCursorCodec;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.settings.UserZone;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class CardEntriesService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long MAX_DAY_TOTAL_DAYS = 92;

    private final CardRepository cards;
    private final CardSpecParser parser;
    private final EntryService entries;
    private final UserZone userZone;
    private final Clock clock;

    public CardEntriesService(CardRepository cards, CardSpecParser parser, EntryService entries,
                              UserZone userZone, Clock clock) {
        this.cards = Objects.requireNonNull(cards, "cards");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.userZone = Objects.requireNonNull(userZone, "userZone");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(readOnly = true)
    public Optional<CardEntriesResponse> get(UUID id, Range range, Integer blockIndex, Integer itemIndex,
                                             LocalDate day, String key, String cursor, Integer requestedLimit) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(range, "range");
        int limit = requestedLimit == null ? 50 : requestedLimit;
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");

        Optional<CardEntity> found = cards.findById(id);
        if (found.isEmpty()) return Optional.empty();
        CardEntity card = found.get();
        CardSpec spec = card.isSystem() ? parser.parseTrusted(JSON.valueToTree(card.getSpec()))
                : parser.parse(JSON.valueToTree(card.getSpec()));
        int selectedBlock = selectBlock(spec, blockIndex);
        Block block = spec.blocks().get(selectedBlock);
        if (itemIndex != null && !(block instanceof GoalsBlock)) {
            throw new IllegalArgumentException("item is only valid for a goals block");
        }
        if (key != null && !(block instanceof BreakdownBlock)) {
            throw new IllegalArgumentException("key is only valid for a breakdown block");
        }
        if (key != null && key.isBlank()) throw new IllegalArgumentException("key must not be blank");

        MetricSpec metric;
        DateRange dates;
        GroupBy bucket;
        String period;
        int selectedItem = itemIndex == null ? 0 : itemIndex;
        if (block instanceof LatestBlock latest) {
            metric = null;
            dates = null;
            bucket = GroupBy.NONE;
            period = "all_time";
            if (day != null) throw new IllegalArgumentException("day is not valid for a latest block");
        } else {
            BlockScope scope;
            if (block instanceof GoalsBlock goals) {
                if (selectedItem < 0 || selectedItem >= goals.items().size()) {
                    throw new IllegalArgumentException("item must select an existing goals item");
                }
                GoalItem goalItem = goals.items().get(selectedItem);
                scope = BlockScope.resolve(goals, goalItem, range, today());
            } else if (block instanceof MetricBlock metricBlock) {
                if (itemIndex != null) throw new IllegalArgumentException("item is only valid for a goals block");
                scope = BlockScope.resolve(metricBlock, range, today());
            } else {
                throw new IllegalArgumentException("selected block does not have an entry metric");
            }
            metric = scope.metric();
            dates = scope.dates();
            bucket = scope.bucket();
            period = periodKey(block, range);
            if (day != null) {
                DateRange narrowed = narrowToBucket(dates, bucket, day);
                dates = narrowed;
                period = "day";
            }
        }

        Map<String, Object> where = new LinkedHashMap<>();
        if (metric != null) where.putAll(metric.where());
        if (key != null) where.put(((BreakdownBlock) block).by(), key);
        List<String> kinds = metric == null ? ((LatestBlock) block).kinds()
                : metric.kinds().equals(List.of("*")) ? List.of() : metric.kinds();
        String numericField = metric != null && metric.op() != AggregateMetric.COUNT ? metric.field() : null;
        EntryFilters filters = new EntryFilters(kinds, null, where, dates == null ? null : dates.from(),
                dates == null ? null : dates.to(), numericField);
        com.tinyme.domain.entries.repository.EntryRepository.EntryCursor decoded = decodeCursor(cursor);
        EntryPage page = entries.page(filters, limit, decoded == null ? null
                : decoded);

        BigDecimal total = null;
        List<DayTotal> dayTotals = null;
        if (metric != null) {
            AggregateResult aggregate = aggregate(metric, where, dates, GroupBy.NONE);
            total = aggregate.value();
            long span = ChronoUnit.DAYS.between(dates.from(), dates.to()) + 1;
            if (span <= MAX_DAY_TOTAL_DAYS) {
                dayTotals = aggregate(metric, where, dates, GroupBy.DAY).buckets().stream()
                        .map(bucketRow -> new DayTotal(bucketRow.period(), bucketRow.value())).toList();
            }
        }

        BlockHeader blockHeader = header(selectedBlock, block, metric, selectedItem);
        return Optional.of(new CardEntriesResponse(
                new CardHeader(card.getId(), spec.title(), wire(spec.icon()), wire(spec.accent()), wire(spec.tone())),
                blockHeader, wire(range), period, dates == null ? null : dates.from(), dates == null ? null : dates.to(),
                new EntryFilter(day, key), total, dayTotals,
                page.entries().stream().map(EntryRow::from).toList(), page.nextCursor()));
    }

    private AggregateResult aggregate(MetricSpec metric, Map<String, Object> where, DateRange dates, GroupBy groupBy) {
        return entries.aggregate(new AggregateQuery(metric.kinds(), metric.op(), metric.field(), where,
                dates.from(), dates.to(), groupBy));
    }

    private static int selectBlock(CardSpec spec, Integer requested) {
        if (requested != null) {
            if (requested < 0 || requested >= spec.blocks().size()) {
                throw new IllegalArgumentException("block must select an existing block");
            }
            return requested;
        }
        if (spec.badge() != null) {
            Block target = spec.blocks().get(spec.badge().block());
            if (hasMetric(target)) return spec.badge().block();
        }
        for (int i = 0; i < spec.blocks().size(); i++) {
            if (hasMetric(spec.blocks().get(i))) return i;
        }
        return 0;
    }

    private static boolean hasMetric(Block block) {
        return block instanceof MetricBlock || block instanceof GoalsBlock goals && !goals.items().isEmpty();
    }

    private static DateRange narrowToBucket(DateRange dates, GroupBy bucket, LocalDate day) {
        if (!bucket.grouped()) throw new IllegalArgumentException("day is only valid for a bucketed block");
        LocalDate start = switch (bucket) {
            case DAY -> day;
            case WEEK -> day.with(java.time.DayOfWeek.MONDAY);
            case MONTH -> day.withDayOfMonth(1);
            case NONE -> throw new IllegalStateException("NONE has no bucket");
        };
        if (!start.equals(day) || day.isBefore(dates.from()) || day.isAfter(dates.to())) {
            throw new IllegalArgumentException("day must be a bucket start inside the block's dates");
        }
        LocalDate end = switch (bucket) {
            case DAY -> start;
            case WEEK -> start.plusDays(6);
            case MONTH -> start.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
            case NONE -> throw new IllegalStateException("NONE has no bucket");
        };
        return new DateRange(start.isBefore(dates.from()) ? dates.from() : start,
                end.isAfter(dates.to()) ? dates.to() : end);
    }

    private static String periodKey(Block block, Range range) {
        Period period = block instanceof MetricBlock metric ? metric.period() : ((GoalsBlock) block).period();
        if (period == Period.RANGE) return switch (range) {
            case DAY -> "today";
            case WEEK -> "this_week";
            case MONTH -> "this_month";
            case YEAR -> "this_year";
        };
        return period.name().toLowerCase(java.util.Locale.ROOT);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(userZone.current()));
    }

    private static com.tinyme.domain.entries.repository.EntryRepository.EntryCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            return EntryCursorCodec.decode(cursor);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("cursor is invalid", invalid);
        }
    }

    private static BlockHeader header(int index, Block block, MetricSpec metric, int selectedItem) {
        String type = BlockTypes.name(block);
        String label = block.label();
        String op = metric == null ? null : metric.op().name().toLowerCase(java.util.Locale.ROOT);
        String unit = switch (block) {
            case StatBlock value -> value.unit();
            case ProgressBlock value -> value.unit();
            case ChartBlock value -> value.unit();
            case BreakdownBlock value -> value.unit();
            case GoalsBlock ignored -> null;
            case CheckinsBlock ignored -> null;
            case LatestBlock ignored -> null;
        };
        if (block instanceof GoalsBlock goals) {
            GoalItem item = goals.items().get(selectedItem);
            label = item.label();
            unit = item.unit();
        }
        return new BlockHeader(index, type, label, op, unit);
    }

    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }

    public record CardEntriesResponse(CardHeader card, BlockHeader block, String range, String period,
                                      LocalDate from, LocalDate to, EntryFilter filter, BigDecimal total,
                                      @JsonProperty("day_totals") List<DayTotal> dayTotals,
                                      List<EntryRow> entries,
                                      @JsonProperty("next_cursor") String nextCursor) {
        public CardEntriesResponse {
            dayTotals = dayTotals == null ? null : List.copyOf(dayTotals);
            entries = List.copyOf(entries);
        }
    }

    public record CardHeader(UUID id, String title, String icon, String accent, String tone) { }
    public record BlockHeader(int index, String type, String label, String op, String unit) { }
    public record EntryFilter(LocalDate day, String key) { }
    public record DayTotal(LocalDate day, BigDecimal value) { }
    public record EntryRow(String id, String ts, @JsonProperty("local_day") String localDay, String kind,
                           BigDecimal quantity, String text, Map<String, Object> data) {
        static EntryRow from(EntrySnapshot entry) {
            return new EntryRow(entry.id().toString(), entry.ts().truncatedTo(ChronoUnit.SECONDS).toString(),
                    entry.localDay().toString(), entry.kind(), entry.quantity(), entry.text(),
                    new LinkedHashMap<>(entry.data()));
        }
    }
}
