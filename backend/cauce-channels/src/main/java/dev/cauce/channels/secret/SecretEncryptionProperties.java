package dev.cauce.channels.secret;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The key that seals provider secrets at rest (ADR 0006, W3), bound from
 * {@code cauce.security.secrets-encryption-key}: base64 of 32 random bytes (AES-256). It lives in
 * the environment, never in the database — an actor holding the database alone cannot open what
 * it seals. Required in prod (no default there). STABLE: changing it makes every sealed value
 * unreadable until key rotation exists (deferred work).
 *
 * <p>The default below exists for local development and tests only, like the API-key pepper's:
 * it is public, so it protects nothing.
 */
@ConfigurationProperties(prefix = "cauce.security")
public class SecretEncryptionProperties {

    /** base64("cauce-dev-only-insecure-key-0001"): 32 bytes, public, dev and tests only. */
    public static final String DEV_ONLY_KEY = "Y2F1Y2UtZGV2LW9ubHktaW5zZWN1cmUta2V5LTAwMDE=";

    private String secretsEncryptionKey = DEV_ONLY_KEY;

    public String getSecretsEncryptionKey() {
        return secretsEncryptionKey;
    }

    public void setSecretsEncryptionKey(String secretsEncryptionKey) {
        this.secretsEncryptionKey = secretsEncryptionKey;
    }
}
