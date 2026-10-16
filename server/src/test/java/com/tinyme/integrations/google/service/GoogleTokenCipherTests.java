package com.tinyme.integrations.google.service;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleTokenCipherTests {
    @Test
    void encryptsAndDecryptsWithTheConfiguredTinkKeyset() throws Exception {
        GoogleTokenCipher cipher = new GoogleTokenCipher(testKeyset());
        byte[] refreshToken = "sample-refresh-token".getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = cipher.encrypt(refreshToken);

        assertThat(cipher.isConfigured()).isTrue();
        assertThat(encrypted).isNotEqualTo(refreshToken);
        assertThat(new String(encrypted, StandardCharsets.UTF_8)).doesNotContain("sample-refresh-token");
        assertThat(cipher.decrypt(encrypted)).containsExactly(refreshToken);
    }

    @Test
    void missingKeyDoesNotBreakStartupButCannotEncryptTokens() {
        GoogleTokenCipher cipher = new GoogleTokenCipher("");

        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt(new byte[]{1}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Set TINYME_ENCRYPTION_KEY before connecting Google");
    }

    @Test
    void rejectsMalformedConfiguredKeyWithoutEchoingItsValue() {
        assertThatThrownBy(() -> new GoogleTokenCipher("definitely-not-a-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TINYME_ENCRYPTION_KEY must be a valid base64 Tink AEAD keyset");
    }

    private static String testKeyset() throws Exception {
        AeadConfig.register();
        KeysetHandle handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM);
        String json = TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get());
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
