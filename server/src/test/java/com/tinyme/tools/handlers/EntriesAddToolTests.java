package com.tinyme.tools.handlers;

import com.tinyme.domain.entries.model.AddCommand;
import com.tinyme.domain.entries.model.AddResult;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EntriesAddToolTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Instant NOW = Instant.parse("2026-10-06T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final UUID ID = UUID.randomUUID();
    private static final ToolContext CTX = new ToolContext(UUID.randomUUID(), UUID.randomUUID(), TOKYO, TODAY, NOW);

    private final EntryService service = mock(EntryService.class);
    private final EntriesAddTool tool = new EntriesAddTool(service);

    @Test
    void normalizesKindAndSuppliesDefaultsFromContext() {
        returns("drink", TODAY, "1", "1");

        ToolResult.Ok ok = (ToolResult.Ok) tool.handle(JSON.readTree("{\"kind\":\" DRINK \"}"), CTX);

        assertThat(tool.name()).isEqualTo("entries_add");
        AddCommand command = capturedCommand();
        assertThat(command.kind()).isEqualTo("drink");
        assertThat(command.quantity()).isEqualByComparingTo("1");
        assertThat(command.ts()).isEqualTo(NOW);
        assertThat(command.zone()).isEqualTo(TOKYO);
        assertThat(command.source()).isEqualTo("chat");
        assertThat(command.text()).isNull();
        assertThat(command.data()).isEmpty();
        assertThat(command.tags()).isEmpty();
        assertThat(ok.summary()).isEqualTo("Logged drink, 1 today");
    }

    @Test
    void forwardsDataAndQuantityAndReturnsAgentResponse() {
        returns("drink", TODAY, "1.5", "2.00");
        ToolResult.Ok ok = (ToolResult.Ok) tool.handle(JSON.readTree("""
                {"kind":"beverage","quantity":1.5,"text":"coffee with Kenji",
                 "data":{"type":"coffee","hours":6.5,"extra":null},"people":["kenji"]}
                """), CTX);

        AddCommand command = capturedCommand();
        assertThat(command.quantity()).isEqualByComparingTo("1.5");
        assertThat(command.text()).isEqualTo("coffee with Kenji");
        assertThat(command.data()).containsEntry("type", "coffee").containsEntry("extra", null);
        assertThat(((Number) command.data().get("hours")).doubleValue()).isEqualTo(6.5);
        JsonNode data = JSON.valueToTree(ok.data());
        assertThat(data.size()).isEqualTo(5);
        assertThat(data.get("id").stringValue()).isEqualTo(ID.toString());
        assertThat(data.get("kind").stringValue()).isEqualTo("drink");
        assertThat(data.get("local_day").stringValue()).isEqualTo("2026-10-06");
        assertThat(data.get("quantity").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(data.get("today_total").decimalValue()).isEqualByComparingTo("2");
        assertThat(ok.summary()).isEqualTo("Logged drink (coffee), 2 today");
    }

    @Test
    void passesOffsetTimestampAndContextZoneForLastNight() {
        returns("drink", TODAY.minusDays(1), "1", "1");
        ToolResult.Ok ok = (ToolResult.Ok) tool.handle(JSON.readTree("""
                {"kind":"drink","ts":"2026-10-05T23:30:00+09:00"}
                """), CTX);

        AddCommand command = capturedCommand();
        assertThat(command.ts()).isEqualTo(Instant.parse("2026-10-05T14:30:00Z"));
        assertThat(command.zone()).isEqualTo(TOKYO);
        assertThat(JSON.valueToTree(ok.data()).get("local_day").stringValue()).isEqualTo("2026-10-05");
    }

    @Test
    void acceptsExactlyOneDayInTheFuture() {
        returns("drink", TODAY.plusDays(1), "1", "1");
        assertThat(tool.handle(JSON.readTree("""
                {"kind":"drink","ts":"2026-10-07T10:00:00+09:00"}
                """), CTX)).isInstanceOf(ToolResult.Ok.class);
        assertThat(capturedCommand().ts()).isEqualTo(NOW.plusSeconds(86400));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"kind\":\" \"}",
            "{\"kind\":\"Drink!\"}",
            "{\"kind\":\"drink\",\"quantity\":0}",
            "{\"kind\":\"drink\",\"quantity\":-1}",
            "{\"kind\":\"drink\",\"quantity\":\"two\"}",
            "{\"kind\":\"drink\",\"ts\":\"2026-10-05T23:30:00\"}",
            "{\"kind\":\"drink\",\"ts\":\"not-a-date\"}",
            "{\"kind\":\"drink\",\"ts\":\"2026-10-07T10:00:00.000000001+09:00\"}",
            "{\"kind\":\"drink\",\"data\":[]}",
            "{\"kind\":\"drink\",\"text\":42}"
    })
    void returnsValidationErrorWithoutCallingService(String input) {
        ToolResult.Err error = (ToolResult.Err) tool.handle(JSON.readTree(input), CTX);
        assertThat(error.code()).isEqualTo("validation_error");
        assertThat(error.message()).isNotBlank();
        verifyNoInteractions(service);
    }

    @Test
    void nonStringTypeIsNotIncludedInSummary() {
        returns("drink", TODAY, "1", "3.50");
        ToolResult.Ok ok = (ToolResult.Ok) tool.handle(JSON.valueToTree(
                Map.of("kind", "drink", "data", Map.of("type", 42))), CTX);
        assertThat(ok.summary()).isEqualTo("Logged drink, 3.5 today");
    }

    @Test
    void serviceFailuresPropagateToDispatcher() {
        when(service.add(any())).thenThrow(new IllegalStateException("database failure"));
        assertThatThrownBy(() -> tool.handle(JSON.readTree("{\"kind\":\"drink\"}"), CTX))
                .isInstanceOf(IllegalStateException.class);
    }

    private void returns(String kind, LocalDate day, String quantity, String total) {
        when(service.add(any())).thenReturn(new AddResult(ID, kind, day,
                new BigDecimal(quantity), new BigDecimal(total)));
    }

    private AddCommand capturedCommand() {
        ArgumentCaptor<AddCommand> captor = ArgumentCaptor.forClass(AddCommand.class);
        verify(service).add(captor.capture());
        return captor.getValue();
    }
}
