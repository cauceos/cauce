package dev.cauce.governance.audit.signing;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;

/**
 * One entry of the published key registry: a {@code key_id}, the public key it names, and the
 * dates that describe its life.
 *
 * <p>{@code retiredAt} means the PRIVATE key stopped signing. It never means the public key
 * goes away: everything that key signed must stay verifiable forever, so a registry entry is
 * only ever added, never removed.
 *
 * <p>{@code compromisedAt} is reported by verification, not treated as a failure. Whether a
 * signature made by a key later found compromised still means anything is a judgement for
 * whoever is auditing. Note the honest limit: without reliable external timestamps, entries
 * signed BEFORE the compromise cannot be told apart from entries signed after it.
 */
public record AuditSigningKey(String keyId,
                              PublicKey publicKey,
                              Instant activatedAt,
                              Instant retiredAt,
                              Instant compromisedAt) {

    public AuditSigningKey {
        Objects.requireNonNull(keyId, "keyId must not be null");
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        if (keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
    }

    /** Whether this key is marked compromised in the registry. */
    public boolean isCompromised() {
        return compromisedAt != null;
    }
}
