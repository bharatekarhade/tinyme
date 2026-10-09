package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.EntrySnapshot;
import com.tinyme.domain.entries.model.EntryWriteResult;
import com.tinyme.domain.entries.service.EntryService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EntriesDeleteToolTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private static final UUID ID = UUID.randomUUID();
    private static final ToolContext CONTEXT = new ToolContext(UUID.randomUUID(), UUID.randomUUID(), TOKYO,
            LocalDate.of(2026, 10, 9), NOW);

    private final EntryService service = mock(EntryService.class);
    private final EntriesDeleteTool tool = new EntriesDeleteTool(service);

    @Test
    void invalidUuidReturnsValidationErrorWithoutCallingService() {
        ToolResult.Err result = (ToolResult.Err) tool.handle(JSON.readTree("{\"id\":\"bad-id\"}"), CONTEXT);

        assertThat(result).isEqualTo(new ToolResult.Err("validation_error", "id must be a UUID"));
        verifyNoInteractions(service);
    }

    @Test
    void returnsDeletedSnapshotAndExpectedSummary() {
        EntrySnapshot snapshot = new EntrySnapshot(ID, Instant.parse("2026-10-08T23:15:00Z"),
                LocalDate.of(2026, 10, 9), "drink", BigDecimal.ONE, null, Map.of("type", "coffee"));
        when(service.delete(ID, NOW)).thenReturn(Optional.of(new EntryWriteResult(snapshot, BigDecimal.ONE)));

        ToolResult.Ok result = (ToolResult.Ok) tool.handle(JSON.readTree("""
                {"id":"%s"}
                """.formatted(ID)), CONTEXT);

        assertThat(result.summary()).isEqualTo("Deleted drink · coffee (9 Oct 08:15), 1 on 9 Oct");
        var entry = JSON.valueToTree(result.data()).get("entry");
        assertThat(entry.get("id").stringValue()).isEqualTo(ID.toString());
        assertThat(entry.get("ts").stringValue()).isEqualTo("2026-10-09T08:15:00+09:00");
        verify(service).delete(ID, NOW);
    }

    @Test
    void unknownIdReturnsNotFound() {
        when(service.delete(any(), eq(NOW))).thenReturn(Optional.empty());

        ToolResult.Err result = (ToolResult.Err) tool.handle(JSON.readTree("""
                {"id":"%s"}
                """.formatted(ID)), CONTEXT);

        assertThat(result.code()).isEqualTo("not_found");
    }
}
