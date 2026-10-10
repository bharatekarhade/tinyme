package com.tinyme.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "account")
public class AccountEntity {
    @Id
    private Short id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    protected AccountEntity() {}

    public static AccountEntity owner(String email, String passwordHash) {
        var account = new AccountEntity();
        account.id = 1;
        account.email = email;
        account.passwordHash = passwordHash;
        return account;
    }

    public Short getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
}
