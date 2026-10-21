package dev.cauce.core.secret;

/**
 * Port: encryption at rest of the provider secrets Cauce must be able to read back (ADR 0006,
 * W3) — a channel's access token, a WhatsApp app secret. Distinct from hashing: an API key or a
 * webhook verification token is only ever compared, so it is hashed; these are used as
 * themselves, so they are sealed. The key lives outside the database.
 */
public interface SecretCipher {

    /** Seals {@code plaintext} into a self-describing, versioned string safe to store. */
    String seal(String plaintext);

    /**
     * Opens a value {@link #seal} produced.
     *
     * @throws IllegalStateException if {@code sealed} is not a sealed value, or does not open
     *     under the configured key (wrong key, or tampered)
     */
    String open(String sealed);

    /** Whether {@code value} is in sealed form (as opposed to a legacy plaintext value). */
    boolean isSealed(String value);
}
