package dev.cauce.tenancy.apikey;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.cauce.core.apikey.ApiKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * In-process cache of successfully-verified API keys, indexed by a one-way digest of
 * the plaintext.
 *
 * <p>A cache hit avoids the database lookup (the prefix query via the SECURITY DEFINER function)
 * and the HMAC verification, so a hot key resolves from memory in microseconds instead of a DB
 * round-trip. The default 5-minute TTL bounds staleness; revocations invalidate eagerly via
 * {@link #invalidateById(UUID)}.
 *
 * <p>An entry never outlives its key: a key with an expiry is cached for the shorter of the TTL and
 * the validity it had left when it was verified, measured against the database clock (ADR 0008).
 * From then on the remaining lifetime is counted by the cache's monotonic ticker, so a hit never
 * consults the application host's wall clock.
 *
 * <p>Storage model:
 * <ul>
 *   <li>Key: SHA-256 of the plaintext API key, encoded as 64 lowercase hex characters.
 *       The plaintext itself is never held in the cache; SHA-256 is appropriate here
 *       because the input is already a 32-character high-entropy random value and so
 *       not subject to rainbow-table attacks.</li>
 *   <li>Value: a {@link CachedApiKey} record carrying the {@code apiKeyId} and
 *       {@code tenantId}, plus the entry's own lifetime. {@code revokedAt} is not stored
 *       because a revoked key is removed from the cache eagerly by
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

    private final Cache<String, Entry> cache;
    private final Duration ttl;

    @Autowired
    public ApiKeyCache(ApiKeyCacheProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    /** With an explicit {@code ticker}: the monotonic time source entry lifetimes are counted on. */
    ApiKeyCache(ApiKeyCacheProperties properties, Ticker ticker) {
        this.ttl = Duration.ofSeconds(properties.getTtlSeconds());
        this.cache = Caffeine.newBuilder()
                .expireAfter(new EntryLifetime())
                .maximumSize(properties.getMaxSize())
                .ticker(ticker)
                .build();
    }

    /** Returns the cached entry for {@code plaintextKey} if present and not expired. */
    public Optional<CachedApiKey> lookup(String plaintextKey) {
        return Optional.ofNullable(cache.getIfPresent(digestOf(plaintextKey))).map(Entry::key);
    }

    /**
     * Caches a successful verification of {@code plaintextKey} against {@code apiKey}, made at
     * {@code now} (a reading of the database clock): for the TTL, or until the key expires if that is
     * sooner. A key already expired at {@code now} is not cached.
     */
    public void put(String plaintextKey, ApiKey apiKey, Instant now) {
        Duration lifetime = ttl;
        if (apiKey.expiresAt() != null) {
            Duration remaining = Duration.between(now, apiKey.expiresAt());
            if (remaining.isNegative() || remaining.isZero()) {
                return;
            }
            lifetime = remaining.compareTo(ttl) < 0 ? remaining : ttl;
        }
        cache.put(digestOf(plaintextKey), new Entry(new CachedApiKey(apiKey.id(), apiKey.tenantId()),
                lifetime.toNanos()));
    }

    /**
     * Removes any cached entry whose {@code apiKeyId} equals {@code apiKeyId}. Called
     * when a key is revoked so the revocation takes effect immediately instead of
     * waiting for the TTL to expire.
     */
    public void invalidateById(UUID apiKeyId) {
        cache.asMap().values().removeIf(v -> v.key().apiKeyId().equals(apiKeyId));
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
     * Snapshot of the fields the authentication filter needs from a verified key. It needs no
     * expiry: an entry is gone from the cache by the time its key expires.
     */
    public record CachedApiKey(UUID apiKeyId, UUID tenantId) {
    }

    /** A cached key with the lifetime it was given when it was verified. */
    private record Entry(CachedApiKey key, long lifetimeNanos) {
    }

    /** Each entry lives for its own lifetime from its write; a read does not extend it. */
    private static final class EntryLifetime implements Expiry<String, Entry> {

        @Override
        public long expireAfterCreate(String key, Entry entry, long currentTime) {
            return entry.lifetimeNanos();
        }

        @Override
        public long expireAfterUpdate(String key, Entry entry, long currentTime, long currentDuration) {
            return entry.lifetimeNanos();
        }

        @Override
        public long expireAfterRead(String key, Entry entry, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }

    /** Registers {@link ApiKeyCacheProperties} for binding. */
    @Configuration
    @EnableConfigurationProperties(ApiKeyCacheProperties.class)
    static class CacheConfig {
    }
}
