package com.tinyme.tools.handlers;

import com.tinyme.domain.cards.service.CardManagementService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CardsListToolTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void rejectsNonEmptyInput() throws Exception {
        var cards = mock(CardManagementService.class);
        var result = new CardsListTool(cards).handle(JSON.readTree("{\"unexpected\":true}"), null);

        assertThat(result).isEqualTo(new com.tinyme.tools.model.ToolResult.Err(
                "validation_error", "Input must be an empty object"));
        verifyNoInteractions(cards);
    }
}
