package com.tinyme.auth.service;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordService {
    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String dummyHash = encoder.encode("tinyme-dummy-password-never-valid");

    public String hash(String password) { return encoder.encode(password); }
    public boolean verify(String password, String hash) { return encoder.matches(password, hash); }
    public boolean verifyUnknownAccount(String password) { return encoder.matches(password, dummyHash); }
}
