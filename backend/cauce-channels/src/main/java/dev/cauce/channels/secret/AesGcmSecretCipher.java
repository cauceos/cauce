package dev.cauce.channels.secret;

import dev.cauce.core.secret.SecretCipher;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM implementation of {@link SecretCipher} (ADR 0006, W3).
 *
 * <pre>
 * sealed = "v1:" + base64( iv[12] || ciphertext || tag[16] )
 * </pre>
 *
 * <p>A fresh random 96-bit IV per seal; the 128-bit GCM tag authenticates the value, so a
 * tampered or wrongly keyed value fails to open instead of decrypting to garbage. The
 * associated data is the version label, so a value cannot be replayed under a future format.
 * The {@code v1:} prefix keeps the format self-describing for the key rotation still deferred.
 */
public final class AesGcmSecretCipher implements SecretCipher {

    static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] ASSOCIATED_DATA = "cauce-secret:v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param base64Key base64 of exactly 32 bytes
     * @throws IllegalStateException if the key is absent or not 32 bytes — at startup, so a
     *     misconfigured instance never runs
     */
    public AesGcmSecretCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("cauce.security.secrets-encryption-key is not configured");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("cauce.security.secrets-encryption-key is not valid base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("cauce.security.secrets-encryption-key must be 32 bytes (AES-256), got "
                    + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    @Override
    public String seal(String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(ASSOCIATED_DATA);
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM sealing failed", e);
        }
    }

    @Override
    public String open(String sealed) {
        if (!isSealed(sealed)) {
            throw new IllegalStateException("value is not sealed");
        }
        byte[] packed;
        try {
            packed = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("sealed value is not valid base64", e);
        }
        if (packed.length <= IV_BYTES) {
            throw new IllegalStateException("sealed value is too short");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES));
            cipher.updateAAD(ASSOCIATED_DATA);
            byte[] plain = cipher.doFinal(packed, IV_BYTES, packed.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Wrong key or tampered value: never fall back to treating it as plaintext.
            throw new IllegalStateException("sealed value does not open under the configured key", e);
        }
    }

    @Override
    public boolean isSealed(String value) {
        return value != null && value.startsWith(PREFIX);
    }
}
