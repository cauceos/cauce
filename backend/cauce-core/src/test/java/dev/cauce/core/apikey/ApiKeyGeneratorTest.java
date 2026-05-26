package dev.cauce.core.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyGeneratorTest {

    @Test
    void newKey_hasFixedFormat() {
        String key = ApiKeyGenerator.newKey();

        assertThat(key).startsWith(ApiKeyGenerator.PREFIX);
        assertThat(key).hasSize(ApiKeyGenerator.TOTAL_LENGTH);
    }

    @Test
    void newKey_consecutiveCalls_returnDistinctKeys() {
        String a = ApiKeyGenerator.newKey();
        String b = ApiKeyGenerator.newKey();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void newKey_suffixIsAlphanumericOnly() {
        for (int i = 0; i < 20; i++) {
            String key = ApiKeyGenerator.newKey();
            String suffix = key.substring(ApiKeyGenerator.PREFIX.length());
            assertThat(suffix).matches("[0-9a-zA-Z]+");
        }
    }

    @Test
    void isValidFormat_acceptsFreshlyGeneratedKeys() {
        for (int i = 0; i < 20; i++) {
            assertThat(ApiKeyGenerator.isValidFormat(ApiKeyGenerator.newKey())).isTrue();
        }
    }

    @Test
    void isValidFormat_rejectsNull() {
        assertThat(ApiKeyGenerator.isValidFormat(null)).isFalse();
    }

    @Test
    void isValidFormat_rejectsWrongPrefix() {
        String key = "sk_" + "a".repeat(32);
        assertThat(ApiKeyGenerator.isValidFormat(key)).isFalse();
    }

    @Test
    void isValidFormat_rejectsWrongLength() {
        assertThat(ApiKeyGenerator.isValidFormat("ck_short")).isFalse();
        assertThat(ApiKeyGenerator.isValidFormat("ck_" + "a".repeat(40))).isFalse();
    }

    @Test
    void isValidFormat_rejectsForbiddenCharacters() {
        String withDash = "ck_" + "a".repeat(31) + "-";
        String withSpace = "ck_" + "a".repeat(31) + " ";
        assertThat(ApiKeyGenerator.isValidFormat(withDash)).isFalse();
        assertThat(ApiKeyGenerator.isValidFormat(withSpace)).isFalse();
    }
}
