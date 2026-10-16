package com.tinyme.integrations.google.service;

import com.tinyme.domain.people.service.PersonService;
import com.tinyme.domain.plans.model.PlanWriteResult;
import com.tinyme.domain.plans.service.PlanService;
import com.tinyme.settings.UserZone;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailSuggestionServiceTests {
    @Test
    void acceptingSuggestionKeepsNotesEmptyAndStoresTheGmailMessageReference() {
        var repository = mock(MailScanRepository.class);
        var plans = mock(PlanService.class);
        var people = mock(PersonService.class);
        var zone = mock(UserZone.class);
        var now = Instant.parse("2026-10-16T00:00:00Z");
        var tokyo = ZoneId.of("Asia/Tokyo");
        var suggestionId = UUID.randomUUID();
        var planId = UUID.randomUUID();
        var suggestion = new MailScanRepository.Suggestion(suggestionId, "gmail-message-1", "event",
                "Flight to Osaka", LocalDate.of(2026, 10, 24), "09:30", null, null,
                "10:00", "America/Los_Angeles", "Haneda", List.of(), 0.9, "Flight confirmed", now);
        when(repository.findNew(suggestionId)).thenReturn(Optional.of(suggestion));
        when(repository.accept(suggestionId, planId)).thenReturn(true);
        when(zone.current()).thenReturn(tokyo);
        when(plans.add(any(), eq(tokyo), eq(now), eq("gmail:gmail-message-1")))
                .thenReturn(new PlanWriteResult(planId, "Flight to Osaka", List.of(), List.of(), List.of()));

        var service = new MailSuggestionService(repository, plans, people, zone, Clock.fixed(now, ZoneId.of("UTC")));

        assertThat(service.accept(suggestionId)).isPresent();
        var command = org.mockito.ArgumentCaptor.forClass(com.tinyme.domain.plans.model.PlanAddCommand.class);
        verify(plans).add(command.capture(), eq(tokyo), eq(now), eq("gmail:gmail-message-1"));
        assertThat(command.getValue().notes()).isNull();
        assertThat(command.getValue().time()).isEqualTo(java.time.LocalTime.of(9, 30));
    }
}
