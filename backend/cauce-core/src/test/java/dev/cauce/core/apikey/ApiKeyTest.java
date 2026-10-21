package dev.cauce.core.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ApiKeyTest {

    private static final UUID TENANT = UUID.randomUUID();

    /** Deterministic test hasher: {@code hash(p) = "h<counter>:" + p}, matches by suffix. */
    private static final class CountingHasher implements ApiKeyHasher {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public String hash(String plaintext) {
            return "h" + counter.incrementAndGet() + ":" + plaintext;
        }

        @Override
        public boolean matches(String plaintext, String hash) {
            int colon = hash.indexOf(':');
            return colon >= 0 && hash.substring(colon + 1).equals(plaintext);
        }
    }

    private static ApiKey freshKey(ApiKeyHasher hasher) {
        return ApiKey.create(TENANT, "Production", ApiKeyGenerator.newKey(), hasher);
    }

    @Test
    void create_setsTimeOrderedUuidV7Id() {
        ApiKey key = freshKey(new CountingHasher());

        assertThat(key.id()).isNotNull();
        assertThat(key.id().version()).isEqualTo(7);
    }

    @Test
    void create_extractsFirst8CharsAsKeyPrefix() {
        ApiKeyHasher hasher = new CountingHasher();
        String plaintext = ApiKeyGenerator.newKey();

        ApiKey key = ApiKey.create(TENANT, "Production", plaintext, hasher);

        assertThat(key.keyPrefix()).isEqualTo(plaintext.substring(0, 8));
    }

    @Test
    void create_storesHashedKeyNotPlaintext() {
        CountingHasher hasher = new CountingHasher();
        String plaintext = ApiKeyGenerator.newKey();

        ApiKey key = ApiKey.create(TENANT, "Production", plaintext, hasher);

        assertThat(key.keyHash()).isNotEqualTo(plaintext);
        assertThat(key.keyHash()).contains(plaintext); // CountingHasher embeds it, but real bcrypt would not
    }

    @Test
    void create_twoKeysFromSamePlaintextHaveDistinctHashes() {
        // The CountingHasher mints a fresh counter prefix every call, mimicking bcrypt's
        // per-call salt behaviour.
        CountingHasher hasher = new CountingHasher();
        String plaintext = ApiKeyGenerator.newKey();

        ApiKey a = ApiKey.create(TENANT, "k1", plaintext, hasher);
        ApiKey b = ApiKey.create(TENANT, "k2", plaintext, hasher);

        assertThat(a.keyHash()).isNotEqualTo(b.keyHash());
    }

    @Test
    void create_rejectsNullTenantId() {
        assertThatThrownBy(() ->
                ApiKey.create(null, "n", ApiKeyGenerator.newKey(), new CountingHasher()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("tenantId");
    }

    @Test
    void create_rejectsBlankName() {
        assertThatThrownBy(() ->
                ApiKey.create(TENANT, " ", ApiKeyGenerator.newKey(), new CountingHasher()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
    }

    @Test
    void create_rejectsMalformedPlaintextKey() {
        assertThatThrownBy(() ->
                ApiKey.create(TENANT, "n", "not-a-key", new CountingHasher()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plaintextKey");
    }

    @Test
    void create_rejectsNullHasher() {
        assertThatThrownBy(() ->
                ApiKey.create(TENANT, "n", ApiKeyGenerator.newKey(), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("hasher");
    }

    @Test
    void matches_returnsTrueForOriginalPlaintext() {
        ApiKeyHasher hasher = new CountingHasher();
        String plaintext = ApiKeyGenerator.newKey();
        ApiKey key = ApiKey.create(TENANT, "n", plaintext, hasher);

        assertThat(key.matches(plaintext, hasher)).isTrue();
    }

    @Test
    void matches_returnsFalseForDifferentPlaintext() {
        ApiKeyHasher hasher = new CountingHasher();
        ApiKey key = ApiKey.create(TENANT, "n", ApiKeyGenerator.newKey(), hasher);

        assertThat(key.matches(ApiKeyGenerator.newKey(), hasher)).isFalse();
    }

    @Test
    void matches_returnsFalseForNullPlaintext() {
        ApiKey key = freshKey(new CountingHasher());

        assertThat(key.matches(null, new CountingHasher())).isFalse();
    }

    @Test
    void isActive_trueOnFreshKey() {
        ApiKey key = freshKey(new CountingHasher());

        assertThat(key.isActiveAt(Instant.now())).isTrue();
        assertThat(key.isRevoked()).isFalse();
        assertThat(key.isExpiredAt(Instant.now())).isFalse();
    }

    @Test
    void revoke_setsRevokedAtAndDeactivates() {
        ApiKey revoked = freshKey(new CountingHasher()).revoke();

        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.isActiveAt(Instant.now())).isFalse();
        assertThat(revoked.revokedAt()).isNotNull();
    }

    @Test
    void revoke_onAlreadyRevoked_throws() {
        ApiKey revoked = freshKey(new CountingHasher()).revoke();

        assertThatThrownBy(revoked::revoke)
                .isInstanceOf(ApiKeyAlreadyRevokedException.class);
    }

    @Test
    void markAsUsed_setsLastUsedAt() {
        ApiKey key = freshKey(new CountingHasher());

        ApiKey used = key.markAsUsed();

        assertThat(used.lastUsedAt()).isNotNull();
        assertThat(key.lastUsedAt()).isNull(); // immutability
    }

    @Test
    void isExpiredAt_trueFromTheExpiryOn() {
        Instant expiry = Instant.parse("2026-10-21T10:00:00Z");
        ApiKey key = ApiKey.rehydrate(UUID.randomUUID(), TENANT, "n", "h", "ck_aaaa",
                expiry.minusSeconds(3600), null, null, expiry);

        assertThat(key.isExpiredAt(expiry)).isTrue();
        assertThat(key.isExpiredAt(expiry.plusSeconds(10))).isTrue();
        assertThat(key.isActiveAt(expiry)).isFalse();
    }

    @Test
    void isExpiredAt_falseBeforeTheExpiry() {
        Instant expiry = Instant.parse("2026-10-21T10:00:00Z");
        ApiKey key = ApiKey.rehydrate(UUID.randomUUID(), TENANT, "n", "h", "ck_aaaa",
                expiry.minusSeconds(3600), null, null, expiry);

        assertThat(key.isExpiredAt(expiry.minusNanos(1000))).isFalse();
        assertThat(key.isActiveAt(expiry.minusSeconds(60))).isTrue();
    }

    @Test
    void isExpiredAt_falseWhenExpiresAtNull() {
        ApiKey key = freshKey(new CountingHasher());

        assertThat(key.isExpiredAt(Instant.now())).isFalse();
    }

    @Test
    void equalsAndHashCode_areBasedOnId() {
        ApiKey key = freshKey(new CountingHasher());
        ApiKey revoked = key.revoke();

        assertThat(revoked).isEqualTo(key);
        assertThat(revoked).hasSameHashCodeAs(key);
    }

    @Test
    void toString_doesNotLeakKeyHash() {
        CountingHasher hasher = new CountingHasher();
        String plaintext = ApiKeyGenerator.newKey();
        ApiKey key = ApiKey.create(TENANT, "n", plaintext, hasher);

        String str = key.toString();

        assertThat(str).doesNotContain(key.keyHash());
    }
}
