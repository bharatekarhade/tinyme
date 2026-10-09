package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.update.EntryPatch;
import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.EntryWriteResult;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EntriesUpdateToolTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final UUID ID = UUID.randomUUID();
    private static final ToolContext CONTEXT = new ToolContext(UUID.randomUUID(), UUID.randomUUID(), TOKYO,
            LocalDate.of(2026, 10, 9), Instant.parse("2026-10-09T00:00:00Z"));

    private final EntryService service = mock(EntryService.class);
    private final EntriesUpdateTool tool = new EntriesUpdateTool(service);

    @Test
    void omittedDataMeansUnchanged() {
        when(service.update(any(), eq(TOKYO))).thenReturn(Optional.empty());

        ToolResult.Err result = (ToolResult.Err) tool.handle(JSON.valueToTree(Map.of("id", ID.toString(),
                "quantity", 2)), CONTEXT);

        assertThat(result.code()).isEqualTo("not_found");
        ArgumentCaptor<EntryPatch> patch = ArgumentCaptor.forClass(EntryPatch.class);
        verify(service).update(patch.capture(), eq(TOKYO));
        assertThat(patch.getValue().data()).isNull();
    }

    @Test
    void emptyDataObjectIsPassedThroughToClearData() {
        when(service.update(any(), eq(TOKYO))).thenReturn(Optional.empty());

        tool.handle(JSON.readTree("""
                {"id":"%s","data":{}}
                """.formatted(ID)), CONTEXT);

        ArgumentCaptor<EntryPatch> patch = ArgumentCaptor.forClass(EntryPatch.class);
        verify(service).update(patch.capture(), eq(TOKYO));
        assertThat(patch.getValue().data()).isEmpty();
    }

    @Test
    void rejectsTimestampMoreThanOneDayAheadBeforeCallingService() {
        ToolResult.Err result = (ToolResult.Err) tool.handle(JSON.readTree("""
                {"id":"%s","quantity":2,"ts":"2026-10-10T09:00:00.000000001+09:00"}
                """.formatted(ID)), CONTEXT);

        assertThat(result.code()).isEqualTo("validation_error");
        assertThat(result.message()).isEqualTo("ts must not be more than one day in the future");
        verifyNoInteractions(service);
    }

    @Test
    void formatsUpdatedSummaryWithTypeAndSecondPrecisionEntryOutput() {
        EntrySnapshot entry = new EntrySnapshot(ID, Instant.parse("2026-10-09T00:30:00.987654321Z"),
                LocalDate.of(2026, 10, 9), "drink", BigDecimal.ONE, null, Map.of("type", "tea"));
        when(service.update(any(), eq(TOKYO))).thenReturn(Optional.of(new EntryWriteResult(entry, BigDecimal.ONE)));

        ToolResult.Ok result = (ToolResult.Ok) tool.handle(JSON.readTree("""
                {"id":"%s","quantity":1}
                """.formatted(ID)), CONTEXT);

        assertThat(result.summary()).isEqualTo("Updated drink · tea: 1 on 9 Oct");
        var entryJson = JSON.valueToTree(result.data()).get("entry");
        assertThat(entryJson.get("ts").stringValue()).isEqualTo("2026-10-09T09:30:00+09:00");
    }
}
