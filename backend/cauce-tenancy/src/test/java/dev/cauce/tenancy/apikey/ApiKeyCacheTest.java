package dev.cauce.tenancy.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.apikey.ApiKey;
import dev.cauce.core.apikey.ApiKeyGenerator;
import dev.cauce.core.apikey.ApiKeyHasher;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiKeyCacheTest {

    private ApiKeyCache cache;
    private ApiKeyHasher hasher;

    @BeforeEach
    void setUp() {
        ApiKeyCacheProperties properties = new ApiKeyCacheProperties();
        properties.setTtlSeconds(300L);
        properties.setMaxSize(100L);
        cache = new ApiKeyCache(properties);
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

        cache.put(plaintext, apiKey);

        ApiKeyCache.CachedApiKey cached = cache.lookup(plaintext).orElseThrow();
        assertThat(cached.apiKeyId()).isEqualTo(apiKey.id());
        assertThat(cached.tenantId()).isEqualTo(apiKey.tenantId());
        assertThat(cached.expiresAt()).isNull();
    }

    @Test
    void lookup_byDifferentPlaintext_returnsEmpty() {
        String stored = ApiKeyGenerator.newKey();
        ApiKey apiKey = ApiKey.create(UUID.randomUUID(), "k", stored, hasher);
        cache.put(stored, apiKey);

        assertThat(cache.lookup(ApiKeyGenerator.newKey())).isEmpty();
    }

    @Test
    void invalidateById_removesEntryForThatId() {
        String plaintext = ApiKeyGenerator.newKey();
        ApiKey apiKey = ApiKey.create(UUID.randomUUID(), "k", plaintext, hasher);
        cache.put(plaintext, apiKey);

        cache.invalidateById(apiKey.id());

        assertThat(cache.lookup(plaintext)).isEmpty();
    }

    @Test
    void invalidateById_doesNotTouchOtherEntries() {
        String pa = ApiKeyGenerator.newKey();
        String pb = ApiKeyGenerator.newKey();
        ApiKey a = ApiKey.create(UUID.randomUUID(), "a", pa, hasher);
        ApiKey b = ApiKey.create(UUID.randomUUID(), "b", pb, hasher);
        cache.put(pa, a);
        cache.put(pb, b);

        cache.invalidateById(a.id());

        assertThat(cache.lookup(pa)).isEmpty();
        assertThat(cache.lookup(pb)).isPresent();
    }

    @Test
    void invalidateAll_clearsEverything() {
        String pa = ApiKeyGenerator.newKey();
        String pb = ApiKeyGenerator.newKey();
        cache.put(pa, ApiKey.create(UUID.randomUUID(), "a", pa, hasher));
        cache.put(pb, ApiKey.create(UUID.randomUUID(), "b", pb, hasher));

        cache.invalidateAll();

        assertThat(cache.size()).isZero();
    }

    @Test
    void cachedApiKey_isExpired_respectsExpiresAt() {
        ApiKeyCache.CachedApiKey neverExpires =
                new ApiKeyCache.CachedApiKey(UUID.randomUUID(), UUID.randomUUID(), null);
        ApiKeyCache.CachedApiKey expired = new ApiKeyCache.CachedApiKey(
                UUID.randomUUID(), UUID.randomUUID(), java.time.Instant.now().minusSeconds(60));
        ApiKeyCache.CachedApiKey future = new ApiKeyCache.CachedApiKey(
                UUID.randomUUID(), UUID.randomUUID(), java.time.Instant.now().plusSeconds(60));

        assertThat(neverExpires.isExpired()).isFalse();
        assertThat(expired.isExpired()).isTrue();
        assertThat(future.isExpired()).isFalse();
    }
}
