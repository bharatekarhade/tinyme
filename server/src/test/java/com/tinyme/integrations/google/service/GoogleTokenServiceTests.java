package com.tinyme.integrations.google.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GoogleTokenServiceTests {
    private final GoogleAccountRepository accounts = mock(GoogleAccountRepository.class);
    private final GoogleTokenCipher cipher = mock(GoogleTokenCipher.class);
    private final GoogleOAuthProvider provider = mock(GoogleOAuthProvider.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T00:00:00Z"));
    private final GoogleTokenService service = new GoogleTokenService(accounts, cipher, provider, clock);
    private final byte[] encrypted = new byte[]{1, 2, 3};

    @BeforeEach
    void setUp() {
        when(accounts.find()).thenReturn(Optional.of(new GoogleAccountRepository.GoogleAccount(
                "owner@example.test", List.of("openid"), encrypted, "connected", clock.instant())));
        when(cipher.decrypt(any(byte[].class))).thenReturn("refresh-token".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void refreshesAndReusesAccessTokenUntilItIsWithinOneMinuteOfExpiry() throws Exception {
        when(provider.refresh("refresh-token")).thenReturn(new GoogleOAuthProvider.AccessToken("access-1", 3600));

        assertThat(service.accessToken()).isEqualTo("access-1");
        assertThat(service.accessToken()).isEqualTo("access-1");

        verify(provider, times(1)).refresh("refresh-token");
    }

    @Test
    void refreshesAgainWhenCachedTokenIsWithinOneMinuteOfExpiry() throws Exception {
        when(provider.refresh("refresh-token"))
                .thenReturn(new GoogleOAuthProvider.AccessToken("access-1", 90))
                .thenReturn(new GoogleOAuthProvider.AccessToken("access-2", 3600));

        assertThat(service.accessToken()).isEqualTo("access-1");
        clock.advanceSeconds(31);
        assertThat(service.accessToken()).isEqualTo("access-2");
        verify(provider, times(2)).refresh("refresh-token");
    }

    @Test
    void invalidGrantMarksAccountAsNeedingReauthorization() throws Exception {
        when(provider.refresh("refresh-token"))
                .thenThrow(new GoogleOAuthProvider.ProviderException(400, "invalid_grant"));

        assertThatThrownBy(service::accessToken)
                .isInstanceOf(GoogleOAuthException.class)
                .hasMessage("google_reauth_required");

        verify(accounts).markNeedsReauth(any(byte[].class));
    }

    @Test
    void refusesRefreshWhenGoogleIsDisconnected() {
        when(accounts.find()).thenReturn(Optional.empty());
        assertThatThrownBy(service::accessToken)
                .isInstanceOf(GoogleOAuthException.class)
                .hasMessage("google_not_connected");
        verifyNoInteractions(provider);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
