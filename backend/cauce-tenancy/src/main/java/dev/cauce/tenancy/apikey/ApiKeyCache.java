package dev.cauce.tenancy.apikey;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.cauce.core.apikey.ApiKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * In-process cache of successfully-verified API keys, indexed by a one-way digest of
 * the plaintext.
 *
 * <p>Bcrypt verification is intentionally expensive (~100ms at the default cost on a
 * modern CPU). A 5-minute TTL cache amortises that cost across requests from the same
 * caller: a cache hit avoids both the database lookup and the bcrypt comparison, so a
 * typical hot key adds microseconds to the request path instead of ~100ms plus a DB
 * round-trip.
 *
 * <p>Storage model:
 * <ul>
 *   <li>Key: SHA-256 of the plaintext API key, encoded as 64 lowercase hex characters.
 *       The plaintext itself is never held in the cache; SHA-256 is appropriate here
 *       because the input is already a 32-character high-entropy random value and so
 *       not subject to rainbow-table attacks.</li>
 *   <li>Value: a {@link CachedApiKey} record carrying the {@code apiKeyId},
 *       {@code tenantId} and {@code expiresAt}. {@code revokedAt} is not stored because
 *       a revoked key is removed from the cache eagerly by
 *       {@link #invalidateById(UUID)}.</li>
 * </ul>
 *
 * <p>Negative caching is deliberately NOT done: each failed attempt pays its own
 * bcrypt cost, which is a natural rate-limit against brute-force probing.
 *
 * <p>Invalidation by API key id ({@link #invalidateById}) is O(n) over the cache. With
 * the default {@code max-size = 10000}, that is sub-millisecond, and revocations are
 * infrequent enough that this is the right trade-off versus maintaining a second
 * index.
 */
@Component
public class ApiKeyCache {

    private final Cache<String, CachedApiKey> cache;

    public ApiKeyCache(ApiKeyCacheProperties properties) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.getTtlSeconds()))
                .maximumSize(properties.getMaxSize())
                .build();
    }

    /** Returns the cached entry for {@code plaintextKey} if present and not expired. */
    public Optional<CachedApiKey> lookup(String plaintextKey) {
        return Optional.ofNullable(cache.getIfPresent(digestOf(plaintextKey)));
    }

    /** Caches a successful verification of {@code plaintextKey} against {@code apiKey}. */
    public void put(String plaintextKey, ApiKey apiKey) {
        cache.put(digestOf(plaintextKey),
                new CachedApiKey(apiKey.id(), apiKey.tenantId(), apiKey.expiresAt()));
    }

    /**
     * Removes any cached entry whose {@code apiKeyId} equals {@code apiKeyId}. Called
     * when a key is revoked so the revocation takes effect immediately instead of
     * waiting for the TTL to expire.
     */
    public void invalidateById(UUID apiKeyId) {
        cache.asMap().values().removeIf(v -> v.apiKeyId().equals(apiKeyId));
    }

    /** Clears every entry. Test hook. */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** Current approximate entry count. Test hook. */
    public long size() {
        return cache.estimatedSize();
    }

    private static String digestOf(String plaintext) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required to be present on every JRE.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Snapshot of the fields the authentication filter needs from a verified key.
     * {@code expiresAt} is intentionally part of the cached value so the filter can
     * evict an expired key without going to the database.
     */
    public record CachedApiKey(UUID apiKeyId, UUID tenantId, Instant expiresAt) {

        /** Returns whether the cached key has reached its (optional) expiry. */
        public boolean isExpired() {
            return expiresAt != null && !Instant.now().isBefore(expiresAt);
        }
    }

    /** Registers {@link ApiKeyCacheProperties} for binding. */
    @Configuration
    @EnableConfigurationProperties(ApiKeyCacheProperties.class)
    static class CacheConfig {
    }
}
