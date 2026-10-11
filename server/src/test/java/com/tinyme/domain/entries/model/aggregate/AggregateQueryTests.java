package com.tinyme.domain.entries.model.aggregate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateQueryTests {
    @Test
    void emptyKindSelectionIsRejectedAndWildcardMustBeAlone() {
        assertThatThrownBy(() -> new AggregateQuery(List.of(), AggregateMetric.COUNT,
                null, Map.of(), null, null, GroupBy.NONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AggregateQuery(List.of("*", "drink"), AggregateMetric.COUNT,
                null, Map.of(), null, null, GroupBy.NONE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void breakdownAllowsOnlyAdditiveMetrics() {
        assertThatThrownBy(() -> new BreakdownQuery(List.of("sleep"), AggregateMetric.AVG,
                "hours", Map.of(), null, null, "type", 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("breakdown metric must be count or sum");
    }

    @Test
    void internalAggregateRangeSupports400BucketsButNotMore() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        new AggregateQuery(List.of("drink"), AggregateMetric.COUNT, null, Map.of(),
                from, from.plusDays(399), GroupBy.DAY);

        assertThatThrownBy(() -> new AggregateQuery(List.of("drink"), AggregateMetric.COUNT, null, Map.of(),
                from, from.plusDays(400), GroupBy.DAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("date range may produce at most 400 buckets");
    }
}
