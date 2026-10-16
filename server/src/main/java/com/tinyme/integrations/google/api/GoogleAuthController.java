package com.tinyme.integrations.google.api;

import com.tinyme.auth.repository.AccountRepository;
import com.tinyme.auth.service.CurrentUser;
import com.tinyme.auth.service.PasswordService;
import com.tinyme.integrations.google.service.GoogleConnection;
import com.tinyme.integrations.google.service.GoogleOAuthException;
import com.tinyme.integrations.google.service.GoogleConnectionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;

@RestController
@RequestMapping("/integrations/google")
public class GoogleAuthController {
    private static final Logger log = LoggerFactory.getLogger(GoogleAuthController.class);
    private static final String APP_CALLBACK = "tinyme://google-connected";

    private final GoogleConnection google;
    private final AccountRepository accounts;
    private final PasswordService passwords;
    private final CurrentUser currentUser;

    public GoogleAuthController(GoogleConnection google,
                                AccountRepository accounts, PasswordService passwords, CurrentUser currentUser) {
        this.google = google;
        this.accounts = accounts;
        this.passwords = passwords;
        this.currentUser = currentUser;
    }

    @PostMapping("/start")
    public ResponseEntity<AuthStart> start() {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AuthStart(google.start()));
        } catch (GoogleOAuthException failure) {
            throw unavailable(failure.reason());
        }
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        if (error != null || code == null || state == null) {
            google.discardState(state);
            return redirect("error", error == null ? "authorization_failed" : "access_denied");
        }
        try {
            google.complete(code, state);
            return redirect("success", null);
        } catch (GoogleOAuthException failure) {
            log.warn("Google OAuth callback rejected ({})", failure.reason());
            return redirect("error", failure.reason());
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Google OAuth callback failed while contacting Google");
            return redirect("error", "google_unavailable");
        }
    }

    @GetMapping
    public ResponseEntity<GoogleConnectionStatus> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(google.status());
    }

    @DeleteMapping
    public ResponseEntity<Void> disconnect(@Valid @RequestBody DisconnectRequest request) {
        var owner = currentUser.requireOwner();
        var account = accounts.findById(owner.id()).orElse(null);
        if (account == null || !passwords.verify(request.password(), account.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Wrong password");
        }
        try {
            google.disconnect();
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not disconnect Google right now");
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static ResponseEntity<Void> redirect(String result, String reason) {
        var builder = UriComponentsBuilder.fromUri(URI.create(APP_CALLBACK)).queryParam("result", result);
        if (reason != null && reason.matches("[a-z_]{1,40}")) builder.queryParam("reason", reason);
        return ResponseEntity.status(HttpStatus.FOUND).location(builder.build().encode().toUri())
                .cacheControl(CacheControl.noStore()).build();
    }

    private static ResponseStatusException unavailable(String reason) {
        String detail = switch (reason) {
            case "encryption_not_configured" -> "Set TINYME_ENCRYPTION_KEY before connecting Google";
            default -> "Google OAuth is not configured on this TinyMe server";
        };
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
    }

    public record AuthStart(@com.fasterxml.jackson.annotation.JsonProperty("auth_url") String authUrl) {}

    public record DisconnectRequest(@NotBlank @Size(max = 256) String password) {}
}
