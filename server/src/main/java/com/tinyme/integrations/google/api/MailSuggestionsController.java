package com.tinyme.integrations.google.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tinyme.integrations.google.service.MailScanRepository;
import com.tinyme.integrations.google.service.MailScanScheduler;
import com.tinyme.integrations.google.service.MailSuggestionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/integrations/google")
public class MailSuggestionsController {
    private final MailSuggestionService suggestions;
    private final MailScanScheduler scans;

    public MailSuggestionsController(MailSuggestionService suggestions, MailScanScheduler scans) {
        this.suggestions = suggestions;
        this.scans = scans;
    }

    @GetMapping("/suggestions")
    public List<SuggestionResponse> list() {
        scans.requestNow();
        return suggestions.listNew().stream().map(SuggestionResponse::from).toList();
    }

    @PostMapping("/scan")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public void requestScan() { scans.requestNow(); }

    @PostMapping("/suggestions/{id}/accept")
    public AcceptedResponse accept(@PathVariable UUID id) {
        return suggestions.accept(id).map(result -> new AcceptedResponse(result.suggestionId(), result.planId(), result.title()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suggestion not found"));
    }

    @PostMapping("/suggestions/{id}/dismiss")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable UUID id) {
        if (!suggestions.dismiss(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Suggestion not found");
    }

    public record SuggestionResponse(UUID id, String type, String title, LocalDate day,
                                     @JsonProperty("start_time") String time,
                                     @JsonProperty("end_time") String endTime,
                                     @JsonProperty("end_day") LocalDate endDay,
                                     @JsonProperty("source_time") String sourceTime,
                                     @JsonProperty("source_time_zone") String sourceTimeZone, String place,
                                     List<String> people, double confidence, String evidence,
                                     @JsonProperty("gmail_message_id") String gmailMessageId,
                                     @JsonProperty("created_at") Instant createdAt) {
        static SuggestionResponse from(MailScanRepository.Suggestion item) {
            return new SuggestionResponse(item.id(), item.type(), item.title(), item.day(), item.time(),
                    item.endTime(), item.endDay(), item.sourceTime(), item.sourceTimeZone(), item.place(), item.people(),
                    item.confidence(), item.evidence(),
                    item.gmailMessageId(), item.createdAt());
        }
    }

    public record AcceptedResponse(@JsonProperty("suggestion_id") UUID suggestionId,
                                   @JsonProperty("plan_id") UUID planId, String title) { }
}
