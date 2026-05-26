package dev.cauce.tenancy.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class BcryptApiKeyHasherTest {

    private final BcryptApiKeyHasher hasher = new BcryptApiKeyHasher(new BCryptPasswordEncoder());

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
    void hash_consecutiveCalls_returnDistinctHashesDueToSalt() {
        String plaintext = "ck_thisIsTheRealKey1234567890abcdefg";

        String a = hasher.hash(plaintext);
        String b = hasher.hash(plaintext);

        assertThat(a).isNotEqualTo(b);
        assertThat(hasher.matches(plaintext, a)).isTrue();
        assertThat(hasher.matches(plaintext, b)).isTrue();
    }

    @Test
    void matches_nullInputs_returnFalse() {
        assertThat(hasher.matches(null, "$2a$10$something")).isFalse();
        assertThat(hasher.matches("anything", null)).isFalse();
    }
}
