package com.tinyme.integrations.google.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class GoogleOAuthStateRepository {
    private final JdbcTemplate jdbc;

    public GoogleOAuthStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String state, String verifier, Instant expiresAt, Instant now) {
        jdbc.update("DELETE FROM oauth_states WHERE expires_at <= ?", Timestamp.from(now));
        jdbc.update("INSERT INTO oauth_states (state, code_verifier, expires_at) VALUES (?, ?, ?)",
                state, verifier, Timestamp.from(expiresAt));
    }

    /** Atomically consumes a valid state so callbacks cannot be replayed. */
    public Optional<String> consume(String state, Instant now) {
        return jdbc.query("DELETE FROM oauth_states WHERE state = ? AND expires_at > ? RETURNING code_verifier",
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), state, Timestamp.from(now));
    }

    public void discard(String state) {
        jdbc.update("DELETE FROM oauth_states WHERE state = ?", state);
    }

    public void clear() {
        jdbc.update("DELETE FROM oauth_states");
    }
}
