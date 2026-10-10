package com.tinyme.auth.controller;

import com.tinyme.auth.model.LoginRequest;
import com.tinyme.auth.model.LoginResponse;
import com.tinyme.auth.repository.AccountRepository;
import com.tinyme.auth.service.AuthException;
import com.tinyme.auth.service.CurrentUser;
import com.tinyme.auth.service.DeviceTokenService;
import com.tinyme.auth.service.LoginRateLimiter;
import com.tinyme.auth.service.PasswordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private static final String INVALID_CREDENTIALS = "Invalid email or password";
    private final AccountRepository accounts;
    private final PasswordService passwords;
    private final DeviceTokenService tokens;
    private final LoginRateLimiter rateLimiter;
    private final CurrentUser currentUser;

    public AuthController(AccountRepository accounts, PasswordService passwords, DeviceTokenService tokens,
                          LoginRateLimiter rateLimiter, CurrentUser currentUser) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.currentUser = currentUser;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        String ip = servletRequest.getRemoteAddr();
        if (!rateLimiter.tryAcquire(ip)) throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts");
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        var account = accounts.findByEmail(email).orElse(null);
        boolean passwordMatches = account == null
                ? passwords.verifyUnknownAccount(request.password())
                : passwords.verify(request.password(), account.getPasswordHash());
        if (account == null || !passwordMatches) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(LoginResponse.from(tokens.issue(request.deviceName())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(name = "Authorization") String authorization) {
        currentUser.requireOwner();
        tokens.revoke(bearerToken(authorization));
        return ResponseEntity.noContent().build();
    }

    private static String bearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return header.substring(7).strip();
    }
}
