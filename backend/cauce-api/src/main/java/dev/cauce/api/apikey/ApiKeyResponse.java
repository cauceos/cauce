package dev.cauce.api.apikey;

import dev.cauce.core.apikey.ApiKey;
import java.time.Instant;
import java.util.UUID;

/**
 * API representation of an {@link ApiKey}: metadata only. The key hash and the plaintext are NEVER
 * exposed here — the plaintext is returned exactly once at creation via
 * {@link ApiKeyCreatedResponse}. Serialised in snake_case (global Jackson naming strategy).
 */
public record ApiKeyResponse(
        UUID id,
        UUID tenantId,
        String keyPrefix,
        String label,
        String status,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt,
        Instant expiresAt) {

    /** The representation of {@code key} as of {@code now}, a reading of the database clock (ADR 0008). */
    public static ApiKeyResponse from(ApiKey key, Instant now) {
        return new ApiKeyResponse(
                key.id(),
                key.tenantId(),
                key.keyPrefix(),
                key.name(),
                status(key, now),
                key.createdAt(),
                key.lastUsedAt(),
                key.revokedAt(),
                key.expiresAt());
    }

    /** Derives a coarse status from the domain's revoked/expired timestamps, at {@code now}. */
    static String status(ApiKey key, Instant now) {
        if (key.isRevoked()) {
            return "REVOKED";
        }
        if (key.isExpiredAt(now)) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }
}
