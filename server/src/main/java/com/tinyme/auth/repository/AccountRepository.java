package com.tinyme.auth.repository;

import com.tinyme.auth.entity.AccountEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AccountRepository {
    private final AccountJpaRepository accounts;

    AccountRepository(AccountJpaRepository accounts) { this.accounts = accounts; }

    public boolean exists() { return accounts.existsById((short) 1); }
    public AccountEntity save(AccountEntity account) { return accounts.save(account); }
    public Optional<AccountEntity> findByEmail(String email) { return accounts.findByEmail(email); }
    public Optional<AccountEntity> findById(short id) { return accounts.findById(id); }
}
