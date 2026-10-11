package com.tinyme.domain.cards.service;

import com.tinyme.domain.cards.entity.CardEntity;
import com.tinyme.domain.cards.model.Badge;
import com.tinyme.domain.cards.model.BadgeKind;
import com.tinyme.domain.cards.model.Block;
import com.tinyme.domain.cards.model.BreakdownBlock;
import com.tinyme.domain.cards.model.CardSpec;
import com.tinyme.domain.cards.model.CardSpecParser;
import com.tinyme.domain.cards.model.CardStatus;
import com.tinyme.domain.cards.model.ChartBlock;
import com.tinyme.domain.cards.model.ChartBucket;
import com.tinyme.domain.cards.model.ChartBuckets;
import com.tinyme.domain.cards.model.CheckinsBlock;
import com.tinyme.domain.cards.model.ComparePeriod;
import com.tinyme.domain.cards.model.GoalItem;
import com.tinyme.domain.cards.model.GoalPeriod;
import com.tinyme.domain.cards.model.GoalsBlock;
import com.tinyme.domain.cards.model.LatestBlock;
import com.tinyme.domain.cards.model.MetricBlock;
import com.tinyme.domain.cards.model.MetricSpec;
import com.tinyme.domain.cards.model.ProgressBlock;
import com.tinyme.domain.cards.model.StatBlock;
import com.tinyme.domain.cards.model.view.BadgeView;
import com.tinyme.domain.cards.model.view.BlockView;
import com.tinyme.domain.cards.model.view.BreakdownView;
import com.tinyme.domain.cards.model.view.CardView;
import com.tinyme.domain.cards.model.view.ChartView;
import com.tinyme.domain.cards.model.view.CheckinsView;
import com.tinyme.domain.cards.model.view.GoalItemView;
import com.tinyme.domain.cards.model.view.GoalsView;
import com.tinyme.domain.cards.model.view.LatestEntryView;
import com.tinyme.domain.cards.model.view.LatestView;
import com.tinyme.domain.cards.model.view.OtherSlice;
import com.tinyme.domain.cards.model.view.Point;
import com.tinyme.domain.cards.model.view.ProgressView;
import com.tinyme.domain.cards.model.view.Slice;
import com.tinyme.domain.cards.model.view.Slot;
import com.tinyme.domain.cards.model.view.StreakView;
import com.tinyme.domain.cards.model.view.StatView;
import com.tinyme.domain.cards.model.view.UnavailableView;
import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.aggregate.AggregateQuery;
import com.tinyme.domain.entries.model.aggregate.AggregateResult;
import com.tinyme.domain.entries.model.aggregate.BreakdownQuery;
import com.tinyme.domain.entries.model.aggregate.BreakdownResult;
import com.tinyme.domain.entries.model.aggregate.DateRange;
import com.tinyme.domain.entries.model.aggregate.GroupBy;
import com.tinyme.domain.entries.model.aggregate.Period;
import com.tinyme.domain.entries.model.aggregate.Range;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.settings.UserZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class CardEvaluator {
    private static final Logger log = LoggerFactory.getLogger(CardEvaluator.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int STREAK_LOOKBACK_DAYS = 400;

    private final CardSpecParser parser;
    private final EntryService entries;
    private final UserZone userZone;
    private final Clock clock;

    public CardEvaluator(CardSpecParser parser, EntryService entries, UserZone userZone, Clock clock) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.userZone = Objects.requireNonNull(userZone, "userZone");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CardView evaluate(CardEntity card, Range range) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(range, "range");
        var zone = userZone.current();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        CardSpec spec;
        try {
            var specNode = JSON.valueToTree(card.getSpec());
            spec = card.isSystem() ? parser.parseTrusted(specNode) : parser.parse(specNode);
        } catch (RuntimeException error) {
            log.warn("Card spec unavailable: card={}, id={}", card.getSlug(), card.getId(), error);
            return new CardView(card.getId(), card.getSlug(), card.getTitle(), null, null, null,
                    CardView.wire(card.getStatus()), null, null, null, "unavailable");
        }

        List<BlockView> views = new ArrayList<>(spec.blocks().size());
        Map<Integer, BlockView> completed = new LinkedHashMap<>();
        for (int i = 0; i < spec.blocks().size(); i++) {
            Block block = spec.blocks().get(i);
            try {
                BlockView view = evaluateBlock(block, range, today);
                views.add(view);
                completed.put(i, view);
            } catch (RuntimeException error) {
                log.warn("Card block unavailable: card={}, block={}, type={}", card.getSlug(), i,
                        block.getClass().getSimpleName(), error);
                var unavailable = new UnavailableView(blockType(block), "unavailable");
                views.add(unavailable);
                completed.put(i, unavailable);
            }
        }

        BadgeView badge = evaluateBadge(spec.badge(), spec.blocks(), completed, range, today);
        return new CardView(card.getId(), card.getSlug(), spec.title(), CardView.wire(spec.icon()),
                CardView.wire(spec.accent()), CardView.wire(spec.tone()), CardView.wire(card.getStatus()),
                card.isSystem(), badge, views, null);
    }

    private BlockView evaluateBlock(Block block, Range range, LocalDate today) {
        return switch (block) {
            case StatBlock stat -> evaluateStat(stat, range, today);
            case ProgressBlock progress -> evaluateProgress(progress, range, today);
            case GoalsBlock goals -> evaluateGoals(goals, range, today);
            case ChartBlock chart -> evaluateChart(chart, range, today);
            case CheckinsBlock checkins -> evaluateCheckins(checkins, range, today);
            case BreakdownBlock breakdown -> evaluateBreakdown(breakdown, range, today);
            case LatestBlock latest -> evaluateLatest(latest);
        };
    }

    private StatView evaluateStat(StatBlock block, Range range, LocalDate today) {
        DateRange dates = block.period().resolve(range, today);
        AggregateResult current = aggregate(block.metric(), dates, GroupBy.NONE);
        BigDecimal previous = null;
        BigDecimal deltaPct = null;
        if (block.compare() == ComparePeriod.PREVIOUS_PERIOD && !isCurrentSingleDay(block, range, dates, today)) {
            AggregateResult prior = aggregate(block.metric(), block.period().previous(range, today), GroupBy.NONE);
            previous = prior.value();
            deltaPct = percentChange(current.value(), previous);
        }
        BigDecimal goal = applies(block.goalPeriod(), block.period(), range) ? block.goal() : null;
        return new StatView(block.label(), block.unit(), current.value(), goal,
                count(current.entries()), previous, deltaPct);
    }

    private ProgressView evaluateProgress(ProgressBlock block, Range range, LocalDate today) {
        AggregateResult result = aggregate(block.metric(), block.period().resolve(range, today), GroupBy.NONE);
        BigDecimal goal = applies(block.goalPeriod(), block.period(), range) ? block.goal() : null;
        BigDecimal value = result.value();
        BigDecimal remaining = goal == null || value == null ? null : goal.subtract(value).max(BigDecimal.ZERO);
        BigDecimal pct = goal == null ? null : percentage(value, goal);
        return new ProgressView(block.label(), block.unit(), lower(block.style().name()),
                value, goal, remaining, pct, count(result.entries()));
    }

    private GoalsView evaluateGoals(GoalsBlock block, Range range, LocalDate today) {
        DateRange dates = block.period().resolve(range, today);
        List<GoalItemView> items = new ArrayList<>(block.items().size());
        for (GoalItem item : block.items()) {
            AggregateResult result = aggregate(item.metric(), dates, GroupBy.NONE);
            BigDecimal value = result.value();
            items.add(new GoalItemView(item.label(), value, item.goal(), percentage(value, item.goal()), item.unit()));
        }
        return new GoalsView(block.label(), items);
    }

    private ChartView evaluateChart(ChartBlock block, Range range, LocalDate today) {
        GroupBy groupBy = toGroupBy(block.bucket() == ChartBucket.AUTO
                ? ChartBuckets.resolveAuto(block.period(), range) : block.bucket());
        DateRange dates = block.period().resolve(range, today);
        if (block.period() == Period.RANGE && range == Range.DAY) {
            dates = new DateRange(today.minusDays(6), today);
        }
        AggregateResult result = aggregate(block.metric(), dates, groupBy);
        List<Point> points = result.buckets().stream().map(bucket -> new Point(bucket.period(), bucket.value(),
                bucket.entries(), state(bucket.period(), groupBy, today))).toList();
        Integer peak = block.highlight() == com.tinyme.domain.cards.model.Highlight.PEAK
                ? peakIndex(points) : null;
        return new ChartView(block.label(), block.unit(), lower(block.style().name()), points,
                block.goalLine(), peak);
    }

    private CheckinsView evaluateCheckins(CheckinsBlock block, Range range, LocalDate today) {
        DateRange dates = block.period().resolve(range, today);
        GroupBy groupBy = checkinsGroupBy(block.period(), range);
        AggregateResult result = aggregate(block.metric(), dates, groupBy);
        List<Slot> slots = result.buckets().stream().map(bucket -> new Slot(bucket.period(),
                state(bucket.period(), groupBy, today), bucket.entries() > 0)).toList();
        int done = (int) slots.stream().filter(Slot::done).count();
        return new CheckinsView(block.label(), slots, done,
                applies(block.goalPeriod(), block.period(), range) ? block.goal() : null,
                streak(block.metric(), today));
    }

    private BreakdownView evaluateBreakdown(BreakdownBlock block, Range range, LocalDate today) {
        DateRange dates = block.period().resolve(range, today);
        BreakdownResult result = entries.breakdown(new BreakdownQuery(block.metric().kinds(), block.metric().op(),
                block.metric().field(), block.metric().where(), dates.from(), dates.to(), block.by(), block.top()));
        List<Slice> slices = result.slices().stream()
                .map(slice -> new Slice(slice.key(), slice.value(), slice.pct())).toList();
        OtherSlice other = new OtherSlice(result.other().value(), result.other().pct());
        return new BreakdownView(block.label(), lower(block.style().name()), block.unit(),
                result.total(), slices, other);
    }

    private LatestView evaluateLatest(LatestBlock block) {
        var found = entries.latest(block.kinds());
        LatestEntryView item = found.map(entry -> latestEntry(entry, block.fields())).orElse(null);
        return new LatestView(block.label(), item);
    }

    private LatestEntryView latestEntry(EntrySnapshot entry, List<String> fields) {
        Map<String, Object> data = new LinkedHashMap<>();
        fields.forEach(field -> {
            if (entry.data().containsKey(field)) data.put(field, entry.data().get(field));
        });
        return new LatestEntryView(entry.ts().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                entry.localDay(), entry.text(), data);
    }

    private AggregateResult aggregate(MetricSpec metric, DateRange dates, GroupBy groupBy) {
        return entries.aggregate(new AggregateQuery(metric.kinds(), metric.op(), metric.field(), metric.where(),
                dates.from(), dates.to(), groupBy));
    }

    private StreakView streak(MetricSpec metric, LocalDate today) {
        LocalDate from = today.minusDays(STREAK_LOOKBACK_DAYS - 1L);
        AggregateResult result = aggregate(metric, new DateRange(from, today), GroupBy.DAY);
        List<Boolean> days = result.buckets().stream().map(bucket -> bucket.entries() > 0).toList();
        int current = 0;
        int end = days.size() - 1;
        if (end >= 0 && !days.get(end)) end--;
        while (end >= 0 && days.get(end)) {
            current++;
            end--;
        }
        int best = 0;
        int run = 0;
        for (boolean hasEntries : days) {
            run = hasEntries ? run + 1 : 0;
            best = Math.max(best, run);
        }
        return new StreakView(current, best);
    }

    private BadgeView evaluateBadge(Badge badge, List<Block> blocks, Map<Integer, BlockView> views,
                                    Range range, LocalDate today) {
        if (badge == null) return null;
        Block target = blocks.get(badge.block());
        try {
            return switch (badge.kind()) {
                case PERIOD -> new BadgeView("period", periodLabel(target instanceof MetricBlock metric
                        ? metric.period() : ((GoalsBlock) target).period(), range), null, null, null, null);
                case DELTA -> {
                    BlockView view = views.get(badge.block());
                    if (!(view instanceof StatView stat) || stat.deltaPct() == null) yield null;
                    var statBlock = (StatBlock) target;
                    yield new BadgeView("delta", null, stat.deltaPct(), statBlock.better() == null
                            ? "neutral" : lower(statBlock.better().name()), compareLabel(statBlock.period(), range), null);
                }
                case STREAK -> {
                    MetricBlock metric = (MetricBlock) target;
                    StreakView result = streak(metric.metric(), today);
                    yield new BadgeView("streak", null, null, null, null, result.current());
                }
            };
        } catch (RuntimeException error) {
            log.warn("Card badge unavailable: block={}, kind={}", badge.block(), badge.kind(), error);
            return null;
        }
    }

    private static boolean applies(GoalPeriod goalPeriod, Period period, Range range) {
        if (goalPeriod == null) return true;
        GoalPeriod resolved = switch (period) {
            case RANGE -> switch (range) {
                case DAY -> GoalPeriod.DAY;
                case WEEK -> GoalPeriod.WEEK;
                case MONTH -> GoalPeriod.MONTH;
                case YEAR -> GoalPeriod.YEAR;
            };
            case TODAY, YESTERDAY -> GoalPeriod.DAY;
            case THIS_WEEK -> GoalPeriod.WEEK;
            case THIS_MONTH -> GoalPeriod.MONTH;
            case THIS_YEAR -> GoalPeriod.YEAR;
            case LAST_7_DAYS, LAST_30_DAYS, LAST_12_WEEKS -> null;
        };
        return goalPeriod == resolved;
    }

    private static boolean isCurrentSingleDay(StatBlock block, Range range, DateRange dates, LocalDate today) {
        return dates.from().equals(today) && dates.to().equals(today)
                && (block.period() == Period.TODAY || block.period() == Period.RANGE && range == Range.DAY);
    }

    private static GroupBy checkinsGroupBy(Period period, Range range) {
        if (period == Period.RANGE) return Period.autoBucket(range);
        return switch (period) {
            case TODAY, YESTERDAY -> GroupBy.DAY;
            case THIS_WEEK, LAST_7_DAYS, THIS_MONTH, LAST_30_DAYS -> GroupBy.DAY;
            case LAST_12_WEEKS -> GroupBy.WEEK;
            case THIS_YEAR -> GroupBy.MONTH;
            case RANGE -> throw new IllegalStateException("RANGE is handled above");
        };
    }

    private static String state(LocalDate period, GroupBy groupBy, LocalDate today) {
        LocalDate point = switch (groupBy) {
            case DAY -> period;
            case WEEK -> period.with(java.time.DayOfWeek.MONDAY);
            case MONTH -> period.withDayOfMonth(1);
            case NONE -> period;
        };
        LocalDate current = switch (groupBy) {
            case DAY -> today;
            case WEEK -> today.with(java.time.DayOfWeek.MONDAY);
            case MONTH -> today.withDayOfMonth(1);
            case NONE -> today;
        };
        return point.isBefore(current) ? "past" : point.isAfter(current) ? "future" : "current";
    }

    private static Integer peakIndex(List<Point> points) {
        Integer peakIndex = null;
        BigDecimal peak = null;
        for (int i = 0; i < points.size(); i++) {
            BigDecimal value = points.get(i).value();
            if (value != null && (peak == null || value.compareTo(peak) > 0)) {
                peak = value;
                peakIndex = i;
            }
        }
        return peakIndex;
    }

    private static BigDecimal percentage(BigDecimal value, BigDecimal goal) {
        if (value == null || goal == null || goal.signum() == 0) return null;
        return value.multiply(BigDecimal.valueOf(100)).divide(goal, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentChange(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null) return null;
        if (previous.signum() == 0) return current.signum() == 0 ? BigDecimal.ZERO : null;
        return current.subtract(previous).multiply(BigDecimal.valueOf(100))
                .divide(previous.abs(), 0, RoundingMode.HALF_UP);
    }

    private static long count(Long entries) { return entries == null ? 0 : entries; }

    private static GroupBy toGroupBy(ChartBucket bucket) {
        return switch (bucket) {
            case DAY -> GroupBy.DAY;
            case WEEK -> GroupBy.WEEK;
            case MONTH -> GroupBy.MONTH;
            case AUTO -> throw new IllegalArgumentException("AUTO bucket must be resolved before querying");
        };
    }

    private static String periodLabel(Period period, Range range) {
        if (period == Period.RANGE) {
            return switch (range) {
                case DAY -> "today";
                case WEEK -> "this_week";
                case MONTH -> "this_month";
                case YEAR -> "this_year";
            };
        }
        return lower(period.name());
    }

    private static String compareLabel(Period period, Range range) {
        if (period == Period.RANGE) {
            return switch (range) {
                case DAY -> "yesterday";
                case WEEK -> "last_week";
                case MONTH -> "last_month";
                case YEAR -> "last_year";
            };
        }
        return switch (period) {
            case RANGE -> throw new IllegalStateException("RANGE handled above");
            case TODAY -> "yesterday";
            case YESTERDAY -> "day_before_yesterday";
            case THIS_WEEK -> "last_week";
            case THIS_MONTH -> "last_month";
            case THIS_YEAR -> "last_year";
            case LAST_7_DAYS -> "previous_7_days";
            case LAST_30_DAYS -> "previous_30_days";
            case LAST_12_WEEKS -> "previous_12_weeks";
        };
    }

    private static String blockType(Block block) {
        return switch (block) {
            case StatBlock ignored -> "stat";
            case ProgressBlock ignored -> "progress";
            case GoalsBlock ignored -> "goals";
            case ChartBlock ignored -> "chart";
            case CheckinsBlock ignored -> "checkins";
            case BreakdownBlock ignored -> "breakdown";
            case LatestBlock ignored -> "latest";
        };
    }

    private static String lower(String value) { return value.toLowerCase(Locale.ROOT); }
}
