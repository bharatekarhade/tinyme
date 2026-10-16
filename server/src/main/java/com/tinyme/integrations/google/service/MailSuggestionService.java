package com.tinyme.integrations.google.service;

import com.tinyme.domain.plans.model.PlanAddCommand;
import com.tinyme.domain.plans.model.PlanKind;
import com.tinyme.domain.plans.service.PlanService;
import com.tinyme.domain.people.model.get.PersonMatchType;
import com.tinyme.domain.people.service.PersonService;
import com.tinyme.settings.UserZone;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class MailSuggestionService {
    private final MailScanRepository suggestions;
    private final PlanService plans;
    private final PersonService people;
    private final UserZone zone;
    private final Clock clock;

    public MailSuggestionService(MailScanRepository suggestions, PlanService plans, PersonService people,
                                 UserZone zone, Clock clock) {
        this.suggestions = suggestions;
        this.plans = plans;
        this.people = people;
        this.zone = zone;
        this.clock = clock;
    }

    public List<MailScanRepository.Suggestion> listNew() {
        suggestions.expireSuggestions(java.time.LocalDate.now(clock.withZone(zone.current())));
        return suggestions.listNew();
    }

    @Transactional
    public Optional<AcceptedSuggestion> accept(UUID id) {
        MailScanRepository.Suggestion suggestion = suggestions.findNew(id).orElse(null);
        if (suggestion == null) return Optional.empty();
        LocalTime time = suggestion.time() == null ? null : LocalTime.parse(suggestion.time());
        LocalTime endTime = suggestion.endTime() == null ? null : LocalTime.parse(suggestion.endTime());
        PlanKind kind = "event".equals(suggestion.type()) ? PlanKind.EVENT : PlanKind.TASK;
        List<String> personSlugs = suggestion.people().stream().map(name -> {
            var lookup = people.get(name);
            return lookup.match() == PersonMatchType.EXACT && lookup.matches().size() == 1
                    ? lookup.matches().getFirst().person().slug() : null;
        }).filter(java.util.Objects::nonNull).distinct().toList();
        var created = plans.add(new PlanAddCommand(kind, suggestion.title(), suggestion.day(), time, endTime, suggestion.endDay(),
                suggestion.place(), personSlugs, null, null, null, null), zone.current(), clock.instant(),
                "gmail:" + suggestion.gmailMessageId());
        if (!suggestions.accept(id, created.id())) throw new IllegalStateException("Suggestion was already handled");
        return Optional.of(new AcceptedSuggestion(id, created.id(), created.title()));
    }

    @Transactional
    public boolean dismiss(UUID id) { return suggestions.dismiss(id); }

    public record AcceptedSuggestion(UUID suggestionId, UUID planId, String title) { }
}
