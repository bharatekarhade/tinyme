package com.tinyme.domain.entries.model.aggregate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
