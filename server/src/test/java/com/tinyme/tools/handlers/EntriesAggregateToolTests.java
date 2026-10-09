package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.aggregate.AggregateMetric;
import com.tinyme.domain.entries.model.aggregate.AggregateResult;
import com.tinyme.domain.entries.model.aggregate.Bucket;
import com.tinyme.domain.entries.model.aggregate.GroupBy;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.tools.model.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EntriesAggregateToolTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final EntryService service = mock(EntryService.class);
    private final EntriesAggregateTool tool = new EntriesAggregateTool(service);

    @Test
    void avgWithoutFieldIsValidationErrorAndDoesNotCallService() {
        ToolResult result = tool.handle(JSON.readTree("""
                {"kind":"sleep","metric":"avg"}
                """), null);

        assertThat(result).isEqualTo(new ToolResult.Err("validation_error", "field is required for metric avg"));
        verify(service, never()).aggregate(any());
    }

    @Test
    void toBeforeFromIsValidationErrorAndDoesNotCallService() {
        ToolResult result = tool.handle(JSON.readTree("""
                {"kind":"sleep","metric":"count","from":"2026-10-09","to":"2026-10-08"}
                """), null);

        assertThat(result).isEqualTo(new ToolResult.Err("validation_error", "to must be on or after from"));
        verify(service, never()).aggregate(any());
    }

    @Test
    void mapsServiceResultToOutputAndActionSummary() {
        LocalDate day = LocalDate.of(2026, 10, 8);
        when(service.aggregate(any())).thenReturn(new AggregateResult("drink", true, AggregateMetric.COUNT,
                null, Map.of("type", "coffee"), day, day, GroupBy.NONE,
                new BigDecimal("2"), 2L, List.of()));

        ToolResult result = tool.handle(JSON.readTree("""
                {"kind":"drink","metric":"count","where":{"type":"coffee"},
                 "from":"2026-10-08","to":"2026-10-08"}
                """), null);

        assertThat(result).isEqualTo(new ToolResult.Ok(Map.of(
                "kind", "drink",
                "kind_known", true,
                "metric", "count",
                "where", Map.of("type", "coffee"),
                "from", "2026-10-08",
                "to", "2026-10-08",
                "value", new BigDecimal("2"),
                "entries", 2L), "drink · coffee: 2 (8 Oct)"));
        verify(service).aggregate(any());
    }

    @Test
    void summarizesAverageFieldAndGroupedDateRange() {
        LocalDate from = LocalDate.of(2026, 10, 6);
        LocalDate to = LocalDate.of(2026, 10, 12);
        when(service.aggregate(any())).thenReturn(new AggregateResult("sleep", true, AggregateMetric.AVG,
                "hours", Map.of(), from, to, GroupBy.DAY, null, null,
                List.of(new Bucket(from, new BigDecimal("7.00"), 5))));

        ToolResult result = tool.handle(JSON.readTree("""
                {"kind":"sleep","metric":"avg","field":"hours","group_by":"day",
                 "from":"2026-10-06","to":"2026-10-12"}
                """), null);

        assertThat(result).isInstanceOf(ToolResult.Ok.class);
        ToolResult.Ok success = (ToolResult.Ok) result;
        assertThat(success.summary()).isEqualTo("sleep hours avg by day: 1 buckets (6–12 Oct)");
        assertThat(success.data()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) success.data();
        assertThat(output).containsEntry("field", "hours").containsEntry("group_by", "day");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) output.get("buckets");
        assertThat(buckets).containsExactly(Map.of(
                "period", "2026-10-06", "value", new BigDecimal("7.00"), "entries", 5L));
    }
}
