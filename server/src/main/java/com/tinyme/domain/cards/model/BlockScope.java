package com.tinyme.domain.cards.model;

import com.tinyme.domain.entries.model.aggregate.DateRange;
import com.tinyme.domain.entries.model.aggregate.GroupBy;
import com.tinyme.domain.entries.model.aggregate.Period;
import com.tinyme.domain.entries.model.aggregate.Range;

import java.time.LocalDate;
import java.util.Objects;

/** The metric and date/bucket scope shared by card evaluation and entry drill-down. */
public record BlockScope(MetricSpec metric, DateRange dates, GroupBy bucket) {
    public BlockScope {
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(dates, "dates");
        Objects.requireNonNull(bucket, "bucket");
    }

    public static BlockScope resolve(MetricBlock block, Range range, LocalDate today) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(today, "today");
        Period period = block.period();
        DateRange dates = period.resolve(range, today);
        GroupBy bucket = GroupBy.NONE;

        if (block instanceof ChartBlock chart) {
            ChartBucket chartBucket = chart.bucket() == ChartBucket.AUTO
                    ? ChartBuckets.resolveAuto(period, range) : chart.bucket();
            bucket = toGroupBy(chartBucket);
            if (period == Period.RANGE && range == Range.DAY) {
                dates = new DateRange(today.minusDays(6), today);
            }
        } else if (block instanceof CheckinsBlock) {
            Range shown = checkinsRange(period, range);
            dates = period.resolve(shown, today);
            bucket = checkinsBucket(period, shown);
        }
        return new BlockScope(block.metric(), dates, bucket);
    }

    /** Resolves an individual goal item, whose GoalsBlock has one metric per item. */
    public static BlockScope resolve(GoalsBlock block, GoalItem item, Range range, LocalDate today) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(today, "today");
        return new BlockScope(item.metric(), block.period().resolve(range, today), GroupBy.NONE);
    }

    /** One day of check-ins is a single dot, so the Day range shows this week with today marked. */
    public static Range checkinsRange(Period period, Range range) {
        return period == Period.RANGE && range == Range.DAY ? Range.WEEK : range;
    }

    private static GroupBy checkinsBucket(Period period, Range range) {
        if (period == Period.RANGE) return Period.autoBucket(range);
        return switch (period) {
            case TODAY, YESTERDAY, THIS_WEEK, LAST_7_DAYS, THIS_MONTH, LAST_30_DAYS -> GroupBy.DAY;
            case LAST_12_WEEKS -> GroupBy.WEEK;
            case THIS_YEAR -> GroupBy.MONTH;
            case RANGE -> throw new IllegalStateException("RANGE is handled above");
        };
    }

    private static GroupBy toGroupBy(ChartBucket bucket) {
        return switch (bucket) {
            case DAY -> GroupBy.DAY;
            case WEEK -> GroupBy.WEEK;
            case MONTH -> GroupBy.MONTH;
            case AUTO -> throw new IllegalArgumentException("AUTO chart bucket must be resolved before querying");
        };
    }
}
