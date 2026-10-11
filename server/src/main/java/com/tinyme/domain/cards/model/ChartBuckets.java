package com.tinyme.domain.cards.model;

import com.tinyme.domain.entries.model.aggregate.GroupBy;
import com.tinyme.domain.entries.model.aggregate.Period;
import com.tinyme.domain.entries.model.aggregate.Range;

import java.util.Set;

/** The chart bucket allow-table and AUTO policy, shared by validation and evaluation. */
public final class ChartBuckets {
    private ChartBuckets() {
    }

    public static Set<ChartBucket> allowed(Period period) {
        return switch (period) {
            case RANGE -> Set.of(ChartBucket.AUTO);
            case THIS_WEEK, LAST_7_DAYS -> Set.of(ChartBucket.DAY);
            case THIS_MONTH, LAST_30_DAYS -> Set.of(ChartBucket.DAY, ChartBucket.WEEK);
            case LAST_12_WEEKS -> Set.of(ChartBucket.WEEK);
            case THIS_YEAR -> Set.of(ChartBucket.WEEK, ChartBucket.MONTH);
            case TODAY, YESTERDAY -> Set.of();
        };
    }

    public static ChartBucket resolveAuto(Period period, Range range) {
        return switch (period) {
            case RANGE -> {
                if (range == null) throw new IllegalArgumentException("range is required to resolve an AUTO chart bucket");
                yield fromGroupBy(Period.autoBucket(range));
            }
            case THIS_WEEK, LAST_7_DAYS, THIS_MONTH, LAST_30_DAYS -> ChartBucket.DAY;
            case LAST_12_WEEKS -> ChartBucket.WEEK;
            case THIS_YEAR -> ChartBucket.MONTH;
            case TODAY, YESTERDAY ->
                    throw new IllegalArgumentException("single-day periods do not support charts; use stat or progress");
        };
    }

    private static ChartBucket fromGroupBy(GroupBy groupBy) {
        return switch (groupBy) {
            case DAY -> ChartBucket.DAY;
            case WEEK -> ChartBucket.WEEK;
            case MONTH -> ChartBucket.MONTH;
            case NONE -> throw new IllegalArgumentException("AUTO chart bucket cannot be none");
        };
    }
}
