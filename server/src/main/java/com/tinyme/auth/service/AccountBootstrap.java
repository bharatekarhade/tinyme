package com.tinyme.auth.service;

import com.tinyme.auth.entity.AccountEntity;
import com.tinyme.auth.repository.AccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Component
@Order(-1)
@DependsOnDatabaseInitialization
public class AccountBootstrap implements ApplicationRunner {
    private final AccountRepository accounts;
    private final PasswordService passwords;
    private final String ownerEmail;
    private final String ownerPassword;

    public AccountBootstrap(AccountRepository accounts, PasswordService passwords,
                            @Value("${TINYME_OWNER_EMAIL:}") String ownerEmail,
                            @Value("${TINYME_OWNER_PASSWORD:}") String ownerPassword) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.ownerEmail = ownerEmail;
        this.ownerPassword = ownerPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (accounts.exists()) return;
        var email = ownerEmail == null ? "" : ownerEmail.strip();
        if (email.isEmpty() || ownerPassword == null || ownerPassword.isBlank()) {
            throw new IllegalStateException("No owner account exists. Set TINYME_OWNER_EMAIL and TINYME_OWNER_PASSWORD before starting TinyMe.");
        }
        if (email.length() > 254 || !email.contains("@")) {
            throw new IllegalStateException("TINYME_OWNER_EMAIL must be a valid email address no longer than 254 characters.");
        }
        if (ownerPassword.length() < 12 || ownerPassword.length() > 256) {
            throw new IllegalStateException("TINYME_OWNER_PASSWORD must be between 12 and 256 characters.");
        }
        accounts.save(AccountEntity.owner(email.toLowerCase(Locale.ROOT), passwords.hash(ownerPassword)));
    }
}
