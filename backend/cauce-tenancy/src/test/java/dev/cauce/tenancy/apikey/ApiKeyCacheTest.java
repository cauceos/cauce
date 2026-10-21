package dev.cauce.tenancy.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.apikey.ApiKey;
import dev.cauce.core.apikey.ApiKeyGenerator;
import dev.cauce.core.apikey.ApiKeyHasher;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiKeyCacheTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    private final AtomicLong ticker = new AtomicLong();
    private ApiKeyCache cache;
    private ApiKeyHasher hasher;

    @BeforeEach
    void setUp() {
        ApiKeyCacheProperties properties = new ApiKeyCacheProperties();
        properties.setTtlSeconds(300L);
        properties.setMaxSize(100L);
        cache = new ApiKeyCache(properties, ticker::get);
        hasher = new ApiKeyHasher() {
            @Override public String hash(String plaintext) { return "h:" + plaintext; }
            @Override public boolean matches(String p, String h) { return h.equals("h:" + p); }
        };
    }

    @Test
    void lookup_whenEmpty_returnsEmpty() {
        assertThat(cache.lookup("ck_anything12345678901234567890ab")).isEmpty();
    }

    @Test
    void put_then_lookup_returnsCachedSnapshot() {
        String plaintext = ApiKeyGenerator.newKey();
        ApiKey apiKey = ApiKey.create(UUID.randomUUID(), "k", plaintext, hasher);

        cache.put(plaintext, apiKey, NOW);

        ApiKeyCache.CachedApiKey cached = cache.lookup(plaintext).orElseThrow();
        assertThat(cached.apiKeyId()).isEqualTo(apiKey.id());
        assertThat(cached.tenantId()).isEqualTo(apiKey.tenantId());
    }

    @Test
    void lookup_byDifferentPlaintext_returnsEmpty() {
        String stored = ApiKeyGenerator.newKey();
        ApiKey apiKey = ApiKey.create(UUID.randomUUID(), "k", stored, hasher);
        cache.put(stored, apiKey, NOW);

        assertThat(cache.lookup(ApiKeyGenerator.newKey())).isEmpty();
    }

    @Test
    void invalidateById_removesEntryForThatId() {
        String plaintext = ApiKeyGenerator.newKey();
        ApiKey apiKey = ApiKey.create(UUID.randomUUID(), "k", plaintext, hasher);
        cache.put(plaintext, apiKey, NOW);

        cache.invalidateById(apiKey.id());

        assertThat(cache.lookup(plaintext)).isEmpty();
    }

    @Test
    void invalidateById_doesNotTouchOtherEntries() {
        String pa = ApiKeyGenerator.newKey();
        String pb = ApiKeyGenerator.newKey();
        ApiKey a = ApiKey.create(UUID.randomUUID(), "a", pa, hasher);
        ApiKey b = ApiKey.create(UUID.randomUUID(), "b", pb, hasher);
        cache.put(pa, a, NOW);
        cache.put(pb, b, NOW);

        cache.invalidateById(a.id());

        assertThat(cache.lookup(pa)).isEmpty();
        assertThat(cache.lookup(pb)).isPresent();
    }

    @Test
    void invalidateAll_clearsEverything() {
        String pa = ApiKeyGenerator.newKey();
        String pb = ApiKeyGenerator.newKey();
        cache.put(pa, ApiKey.create(UUID.randomUUID(), "a", pa, hasher), NOW);
        cache.put(pb, ApiKey.create(UUID.randomUUID(), "b", pb, hasher), NOW);

        cache.invalidateAll();

        assertThat(cache.size()).isZero();
    }

    @Test
    void aKeyWithoutExpiry_isCachedForTheTtl() {
        String plaintext = ApiKeyGenerator.newKey();
        cache.put(plaintext, ApiKey.create(UUID.randomUUID(), "k", plaintext, hasher), NOW);

        advance(Duration.ofSeconds(299));
        assertThat(cache.lookup(plaintext)).isPresent();
        advance(Duration.ofSeconds(1));
        assertThat(cache.lookup(plaintext)).isEmpty();
    }

    @Test
    void aKeyExpiringBeforeTheTtl_isCachedOnlyUntilItsExpiry_countedFromTheDatabaseNow() {
        String plaintext = ApiKeyGenerator.newKey();
        cache.put(plaintext, expiringAt(plaintext, NOW.plusSeconds(60)), NOW);

        // The remaining validity is measured against the database clock's NOW, then counted by the
        // monotonic ticker: the host's wall clock never takes part.
        advance(Duration.ofSeconds(59));
        assertThat(cache.lookup(plaintext)).isPresent();
        advance(Duration.ofSeconds(1));
        assertThat(cache.lookup(plaintext)).isEmpty();
    }

    @Test
    void aKeyAlreadyExpiredAtTheDatabaseNow_isNotCached() {
        String plaintext = ApiKeyGenerator.newKey();

        cache.put(plaintext, expiringAt(plaintext, NOW), NOW);

        assertThat(cache.lookup(plaintext)).isEmpty();
    }

    private ApiKey expiringAt(String plaintext, Instant expiresAt) {
        ApiKey created = ApiKey.create(UUID.randomUUID(), "k", plaintext, hasher);
        return ApiKey.rehydrate(created.id(), created.tenantId(), created.name(), created.keyHash(),
                created.keyPrefix(), created.createdAt(), null, null, expiresAt);
    }

    private void advance(Duration duration) {
        ticker.addAndGet(duration.toNanos());
    }
}
