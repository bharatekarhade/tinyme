package com.tinyme.integrations.google.api;

import com.tinyme.integrations.google.service.GmailConfirmationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/conversations/confirmations")
public class GmailConfirmationController {
    private final GmailConfirmationService confirmations;

    public GmailConfirmationController(GmailConfirmationService confirmations) {
        this.confirmations = confirmations;
    }

    @GetMapping
    public List<GmailConfirmationService.Pending> pending() {
        return confirmations.pending();
    }

    @PostMapping("/{id}")
    public GmailConfirmationService.Decision decide(@PathVariable UUID id,
                                                     @Valid @RequestBody DecisionRequest request) {
        try {
            return confirmations.decide(id, request.decision());
        } catch (GmailConfirmationService.MissingConfirmationException missing) {
            throw new ResponseStatusException(HttpStatus.GONE, "This send confirmation expired or was already handled");
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gmail could not send this draft");
        }
    }

    public record DecisionRequest(@NotBlank @Pattern(regexp = "send|cancel") String decision) {}
}
