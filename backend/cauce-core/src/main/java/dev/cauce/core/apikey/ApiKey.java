package dev.cauce.core.apikey;

import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A static API key used to authenticate HTTP requests on behalf of a tenant.
 *
 * <p>Pure domain type: no persistence or framework dependencies. Immutable; created
 * via {@link #create} with a time-ordered UUIDv7 id, a freshly-hashed key, and the
 * non-secret 8-character {@code keyPrefix} taken verbatim from the plaintext.
 *
 * <p>The plaintext key is never stored on the instance: only {@code keyHash}
 * (produced by an {@link ApiKeyHasher}) and {@code keyPrefix} are persisted. The
 * plaintext is returned exactly once, in the result of the application-service
 * factory, and is then irrecoverable.
 *
 * <p>{@code keyPrefix} is a short non-secret slice that acts as a fast lookup index
 * in the authentication filter: bcrypt verification is too expensive to run over the
 * whole table, but it is cheap to fetch the (typically 0-1) rows that share a prefix
 * and verify only those.
 *
 * <p>Lifecycle: a fresh key is active; {@link #markAsUsed()} stamps the last successful
 * use; {@link #revoke()} is a one-way transition to revoked. Expiry is time-based and
 * inferred from {@code expiresAt}, not stored as state.
 */
public final class ApiKey {

    private static final int PREFIX_LENGTH = 8;

    private final UUID id;
    private final UUID tenantId;
    private final String name;
    private final String keyHash;
    private final String keyPrefix;
    private final Instant createdAt;
    private final Instant lastUsedAt; // null until first use
    private final Instant revokedAt;  // null while active
    private final Instant expiresAt;  // null means never expires

    private ApiKey(UUID id, UUID tenantId, String name, String keyHash, String keyPrefix,
                   Instant createdAt, Instant lastUsedAt, Instant revokedAt, Instant expiresAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
        this.revokedAt = revokedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * Creates a never-expiring active key owned by {@code tenantId}. The {@code plaintextKey}
     * must satisfy {@link ApiKeyGenerator#isValidFormat}; its first
     * {@value #PREFIX_LENGTH} characters become the visible {@code keyPrefix}, and the
     * full plaintext is hashed by {@code hasher} and discarded.
     */
    public static ApiKey create(UUID tenantId, String name, String plaintextKey, ApiKeyHasher hasher) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(hasher, "hasher");
        String trimmedName = requireText(name, "name");
        if (!ApiKeyGenerator.isValidFormat(plaintextKey)) {
            throw new IllegalArgumentException(
                    "plaintextKey does not match the expected format");
        }
        String prefix = plaintextKey.substring(0, PREFIX_LENGTH);
        String hash = hasher.hash(plaintextKey);
        return new ApiKey(
                UuidGenerator.newV7(),
                tenantId,
                trimmedName,
                hash,
                prefix,
                Instant.now(),
                null,
                null,
                null);
    }

    /** Rebuilds an API key from already-persisted state. For the persistence layer only. */
    public static ApiKey rehydrate(UUID id, UUID tenantId, String name, String keyHash,
                                   String keyPrefix, Instant createdAt, Instant lastUsedAt,
                                   Instant revokedAt, Instant expiresAt) {
        return new ApiKey(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(keyHash, "keyHash"),
                Objects.requireNonNull(keyPrefix, "keyPrefix"),
                Objects.requireNonNull(createdAt, "createdAt"),
                lastUsedAt,
                revokedAt,
                expiresAt);
    }

    // === LIFECYCLE TRANSITIONS (IMMUTABLE - RETURN NEW INSTANCES) ===

    /** Returns a copy with {@code lastUsedAt} set to {@code Instant.now()}. */
    public ApiKey markAsUsed() {
        return new ApiKey(id, tenantId, name, keyHash, keyPrefix, createdAt, Instant.now(),
                revokedAt, expiresAt);
    }

    /**
     * Returns a revoked copy. Revocation is one-way: calling this on an already-revoked
     * key throws {@link ApiKeyAlreadyRevokedException}.
     */
    public ApiKey revoke() {
        if (revokedAt != null) {
            throw new ApiKeyAlreadyRevokedException(
                    "ApiKey " + id + " was already revoked at " + revokedAt);
        }
        return new ApiKey(id, tenantId, name, keyHash, keyPrefix, createdAt, lastUsedAt,
                Instant.now(), expiresAt);
    }

    // === VERIFICATION ===

    /**
     * Returns whether {@code plaintextKey} hashes to this key's stored hash. Does NOT
     * check {@link #isActive()}; the caller is responsible for both.
     */
    public boolean matches(String plaintextKey, ApiKeyHasher hasher) {
        Objects.requireNonNull(hasher, "hasher");
        if (plaintextKey == null) {
            return false;
        }
        return hasher.matches(plaintextKey, keyHash);
    }

    /** Returns whether the key is currently usable (not revoked and not expired). */
    public boolean isActive() {
        return !isRevoked() && !isExpired();
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return expiresAt != null && !Instant.now().isBefore(expiresAt);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    // === ACCESSORS ===

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String name() {
        return name;
    }

    public String keyHash() {
        return keyHash;
    }

    public String keyPrefix() {
        return keyPrefix;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof ApiKey other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        // keyHash is intentionally omitted so it cannot leak through logs.
        return "ApiKey[id=%s, tenantId=%s, name=%s, keyPrefix=%s, revoked=%s, expired=%s]"
                .formatted(id, tenantId, name, keyPrefix, isRevoked(), isExpired());
    }
}
