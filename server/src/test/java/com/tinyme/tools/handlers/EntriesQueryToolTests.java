package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.query.EntryQuery;
import com.tinyme.domain.entries.model.query.EntryQueryResult;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EntriesQueryToolTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final ToolContext CONTEXT = new ToolContext(UUID.randomUUID(), UUID.randomUUID(), TOKYO,
            LocalDate.of(2026, 10, 9), Instant.parse("2026-10-09T00:00:00Z"));
    private final EntryService service = mock(EntryService.class);
    private final EntriesQueryTool tool = new EntriesQueryTool(service);

    @ParameterizedTest
    @ValueSource(ints = {0, 21})
    void rejectsOutOfRangeLimitWithoutCallingService(int limit) {
        ToolResult result = tool.handle(JSON.readTree("{\"limit\":" + limit + "}"), CONTEXT);

        assertThat(result).isInstanceOf(ToolResult.Err.class);
        assertThat(((ToolResult.Err) result).code()).isEqualTo("validation_error");
        verify(service, never()).query(any());
    }

    @Test
    void rejectsToBeforeFromWithoutCallingService() {
        ToolResult result = tool.handle(JSON.readTree("""
                {"from":"2026-10-09","to":"2026-10-08"}
                """), CONTEXT);

        assertThat(result).isEqualTo(new ToolResult.Err("validation_error", "to must be on or after from"));
        verify(service, never()).query(any());
    }

    @Test
    void formatsTimestampInContextZoneAndReturnsIdAndSummary() {
        UUID id = UUID.randomUUID();
        EntrySnapshot snapshot = new EntrySnapshot(id, Instant.parse("2026-10-08T14:30:00.123456789Z"),
                LocalDate.of(2026, 10, 8), "drink", BigDecimal.ONE, null, Map.of("type", "beer"));
        when(service.query(any())).thenReturn(new EntryQueryResult(true, List.of(snapshot), false));

        ToolResult.Ok result = (ToolResult.Ok) tool.handle(JSON.readTree("""
                {"kind":"drink","where":{"type":"beer"},"from":"2026-10-08","to":"2026-10-08"}
                """), CONTEXT);

        JsonNode output = JSON.valueToTree(result.data());
        assertThat(output.get("kind_known").booleanValue()).isTrue();
        assertThat(output.get("truncated").booleanValue()).isFalse();
        assertThat(output.get("entries").get(0).get("id").stringValue()).isEqualTo(id.toString());
        assertThat(output.get("entries").get(0).get("ts").stringValue())
                .isEqualTo("2026-10-08T23:30:00+09:00");
        assertThat(result.summary()).isEqualTo("Found 1 drink · beer (latest 8 Oct)");

        ArgumentCaptor<EntryQuery> query = ArgumentCaptor.forClass(EntryQuery.class);
        verify(service).query(query.capture());
        assertThat(query.getValue().limit()).isEqualTo(10);
        assertThat(query.getValue().where()).containsEntry("type", "beer");
    }

    @Test
    void emptyResultUsesNoMatchSummary() {
        when(service.query(any())).thenReturn(new EntryQueryResult(false, List.of(), false));

        ToolResult.Ok result = (ToolResult.Ok) tool.handle(JSON.readTree("{\"kind\":\"beverage\"}"), CONTEXT);

        assertThat(result.summary()).isEqualTo("No matching entries");
        assertThat(JSON.valueToTree(result.data()).get("kind_known").booleanValue()).isFalse();
    }
}
