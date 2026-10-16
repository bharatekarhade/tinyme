package com.tinyme.integrations.google.service;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/** Encrypts the Google refresh token using a server-held Tink AEAD keyset. */
public final class GoogleTokenCipher {
    private static final byte[] ASSOCIATED_DATA = "google_account:1".getBytes(StandardCharsets.UTF_8);
    private final Aead aead;

    public GoogleTokenCipher(String base64Keyset) {
        if (base64Keyset == null || base64Keyset.isBlank()) {
            this.aead = null;
            return;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(base64Keyset.replaceAll("\\s", ""));
            String keysetJson = new String(decoded, StandardCharsets.UTF_8);
            AeadConfig.register();
            KeysetHandle handle = TinkJsonProtoKeysetFormat.parseKeyset(
                    keysetJson, InsecureSecretKeyAccess.get());
            this.aead = handle.getPrimitive(RegistryConfiguration.get(), Aead.class);
        } catch (IllegalArgumentException | GeneralSecurityException error) {
            throw new IllegalStateException("TINYME_ENCRYPTION_KEY must be a valid base64 Tink AEAD keyset", error);
        }
    }

    public boolean isConfigured() {
        return aead != null;
    }

    public byte[] encrypt(byte[] plaintext) {
        return crypt(() -> aead().encrypt(plaintext, ASSOCIATED_DATA));
    }

    public byte[] decrypt(byte[] ciphertext) {
        return crypt(() -> aead().decrypt(ciphertext, ASSOCIATED_DATA));
    }

    private Aead aead() {
        if (aead == null) {
            throw new IllegalStateException("Set TINYME_ENCRYPTION_KEY before connecting Google");
        }
        return aead;
    }

    private static byte[] crypt(CryptoOperation operation) {
        try {
            return operation.run();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Google token encryption operation failed", error);
        }
    }

    @FunctionalInterface
    private interface CryptoOperation {
        byte[] run() throws GeneralSecurityException;
    }
}
