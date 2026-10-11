package com.tinyme.tools.handlers;

import com.tinyme.domain.cards.model.view.CardView;
import com.tinyme.domain.cards.model.view.ChartView;
import com.tinyme.domain.cards.model.view.Point;
import com.tinyme.domain.cards.model.view.StatView;
import com.tinyme.domain.cards.service.CardManagementService;
import com.tinyme.tools.model.ToolContext;
import com.tinyme.tools.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CardsSaveToolTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ID = UUID.randomUUID();
    private static final ToolContext CONTEXT = new ToolContext(UUID.randomUUID(), UUID.randomUUID(),
            ZoneId.of("Asia/Tokyo"), LocalDate.of(2026, 10, 11), Instant.parse("2026-10-11T01:00:00Z"));

    private CardManagementService cards;
    private CardsSaveTool tool;

    @BeforeEach
    void setUp() {
        cards = mock(CardManagementService.class);
        tool = new CardsSaveTool(cards);
    }

    @Test
    void mapsEachServiceOutcomeToItsToolErrorCode() throws Exception {
        when(cards.save(any(), any(), anyBoolean(), any())).thenReturn(
                new CardManagementService.SaveOutcome.Duplicate(ID),
                new CardManagementService.SaveOutcome.NotFound(),
                new CardManagementService.SaveOutcome.SystemCard());

        assertThat(error("{\"spec\":{}}")).extracting(ToolResult.Err::code).isEqualTo("duplicate");
        assertThat(error("{\"id\":\"" + ID + "\",\"status\":\"active\"}"))
                .extracting(ToolResult.Err::code).isEqualTo("not_found");
        assertThat(error("{\"spec\":{}}")).extracting(ToolResult.Err::code).isEqualTo("system_card");
    }

    @Test
    void malformedUuidAndDraftStatusAreValidationErrorsWithoutCallingService() throws Exception {
        assertThat(error("{\"id\":\"bad\",\"status\":\"active\"}"))
                .isEqualTo(new ToolResult.Err("validation_error", "id must be a UUID"));
        assertThat(error("{\"id\":\"" + ID + "\",\"status\":\"draft\"}"))
                .isEqualTo(new ToolResult.Err("validation_error", "status must be 'active'"));
        verifyNoInteractions(cards);
    }

    @Test
    void returnsCompactHeadlinesInsteadOfTheEvaluatedCard() throws Exception {
        var view = new CardView(ID, "calories", "Calories", "flame", "coral", "surface",
                "draft", false, null,
                List.of(
                        new StatView("kcal", "kcal", new BigDecimal("155"), new BigDecimal("2200"), 2,
                                null, null),
                        new ChartView("daily", "JPY", "bars", List.of(
                                new Point(LocalDate.of(2026, 10, 5), BigDecimal.ONE, 1, "past"),
                                new Point(LocalDate.of(2026, 10, 6), BigDecimal.TWO, 1, "past"),
                                new Point(LocalDate.of(2026, 10, 7), BigDecimal.ONE, 1, "past"),
                                new Point(LocalDate.of(2026, 10, 8), BigDecimal.ZERO, 0, "past"),
                                new Point(LocalDate.of(2026, 10, 9), BigDecimal.TEN, 1, "past"),
                                new Point(LocalDate.of(2026, 10, 10), BigDecimal.ZERO, 0, "past"),
                                new Point(LocalDate.of(2026, 10, 11), BigDecimal.valueOf(3), 1, "current")),
                                null, null)), null);
        when(cards.save(isNull(), any(), eq(false), any())).thenReturn(
                new CardManagementService.SaveOutcome.Saved(view, List.of("no meal entries yet"), true, false, false));

        ToolResult.Ok result = (ToolResult.Ok) tool.handle(JSON.readTree("{\"spec\":{}}") , CONTEXT);

        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) result.data();
        assertThat(data).containsOnlyKeys("id", "status", "title", "headline", "warnings");
        assertThat(data).doesNotContainKey("card");
        assertThat(data.get("headline").toString()).contains("stat", "kcal", "155", "2200");
        assertThat(data.get("headline").toString()).contains("chart", "daily", "2026-10-11").doesNotContain("2026-10-05");
        assertThat(result.ref()).containsEntry("card_id", ID.toString());
        assertThat(result.summary()).isEqualTo("Saved draft card · Calories");
    }

    private ToolResult.Err error(String input) throws Exception {
        return (ToolResult.Err) tool.handle(JSON.readTree(input), CONTEXT);
    }
}
