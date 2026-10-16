package com.tinyme.integrations.google.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class GoogleAccountRepository {
    private final JdbcTemplate jdbc;

    public GoogleAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<GoogleAccount> find() {
        return jdbc.query("""
                SELECT email, scopes, refresh_token_enc, status, connected_at
                FROM google_account WHERE id = 1
                """, rs -> {
            if (!rs.next()) return Optional.empty();
            try {
                String[] scopes = (String[]) rs.getArray("scopes").getArray();
                return Optional.of(new GoogleAccount(rs.getString("email"), List.of(scopes),
                        rs.getBytes("refresh_token_enc"), rs.getString("status"),
                        rs.getTimestamp("connected_at").toInstant()));
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not read Google account", exception);
            }
        });
    }

    public void save(String email, List<String> scopes, byte[] encryptedRefreshToken, Instant now) {
        jdbc.update("""
                INSERT INTO google_account (id, email, scopes, refresh_token_enc, status, connected_at, updated_at)
                VALUES (1, ?, string_to_array(?, ' '), ?, 'connected', ?, ?)
                ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email, scopes = EXCLUDED.scopes,
                    refresh_token_enc = EXCLUDED.refresh_token_enc, status = 'connected', connected_at = EXCLUDED.connected_at
                """, email, String.join(" ", scopes), encryptedRefreshToken,
                Timestamp.from(now), Timestamp.from(now));
    }

    public void delete() {
        jdbc.update("DELETE FROM google_account WHERE id = 1");
    }

    public void markNeedsReauth(byte[] refreshTokenFingerprint) {
        jdbc.update("UPDATE google_account SET status = 'needs_reauth' WHERE id = 1 AND refresh_token_enc = ?",
                refreshTokenFingerprint);
    }

    public record GoogleAccount(String email, List<String> scopes, byte[] encryptedRefreshToken,
                                String status, Instant connectedAt) {
        public GoogleAccount {
            scopes = List.copyOf(scopes);
            encryptedRefreshToken = Arrays.copyOf(encryptedRefreshToken, encryptedRefreshToken.length);
        }

        @Override public byte[] encryptedRefreshToken() {
            return Arrays.copyOf(encryptedRefreshToken, encryptedRefreshToken.length);
        }
    }
}
