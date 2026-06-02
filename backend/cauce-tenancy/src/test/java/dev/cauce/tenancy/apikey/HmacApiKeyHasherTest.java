package dev.cauce.tenancy.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HmacApiKeyHasherTest {

    private final HmacApiKeyHasher hasher = new HmacApiKeyHasher(pepper("unit-test-pepper"));

    private static ApiKeyPepperProperties pepper(String value) {
        ApiKeyPepperProperties p = new ApiKeyPepperProperties();
        p.setApiKeyPepper(value);
        return p;
    }

    @Test
    void hash_doesNotReturnPlaintext() {
        String plaintext = "ck_thisIsTheRealKey1234567890abcdefg";

        String hash = hasher.hash(plaintext);

        assertThat(hash).isNotEqualTo(plaintext);
        assertThat(hash).doesNotContain(plaintext);
    }

    @Test
    void matches_returnsTrueForOriginalPlaintext() {
        String plaintext = "ck_thisIsTheRealKey1234567890abcdefg";
        String hash = hasher.hash(plaintext);

        assertThat(hasher.matches(plaintext, hash)).isTrue();
    }

    @Test
    void matches_returnsFalseForDifferentPlaintext() {
        String hash = hasher.hash("ck_one1234567890123456789012345678");

        assertThat(hasher.matches("ck_two1234567890123456789012345678", hash)).isFalse();
    }

    @Test
    void hash_isDeterministic_forSamePepper() {
        String plaintext = "ck_thisIsTheRealKey1234567890abcdefg";

        // Unlike bcrypt, HMAC is deterministic — this is what lets the verify path re-derive it.
        assertThat(hasher.hash(plaintext)).isEqualTo(hasher.hash(plaintext));
    }

    @Test
    void hash_differsAcrossPeppers_andDoesNotVerifyUnderAnother() {
        String plaintext = "ck_thisIsTheRealKey1234567890abcdefg";
        HmacApiKeyHasher other = new HmacApiKeyHasher(pepper("a-different-pepper"));

        assertThat(hasher.hash(plaintext)).isNotEqualTo(other.hash(plaintext));
        assertThat(other.matches(plaintext, hasher.hash(plaintext))).isFalse();
    }

    @Test
    void matches_nullInputs_returnFalse() {
        assertThat(hasher.matches(null, "abcdef")).isFalse();
        assertThat(hasher.matches("anything", null)).isFalse();
    }

    @Test
    void matches_nonHexHash_returnsFalse() {
        assertThat(hasher.matches("anything", "not-a-hex-string!!")).isFalse();
    }
}
