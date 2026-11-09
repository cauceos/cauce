package dev.cauce.channels.webchat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PublicKeysTest {

    @Test
    void newKey_hasTheShapeTheLookupChecks_andDoesNotRepeat() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String key = PublicKeys.newKey();
            assertThat(PublicKeys.isValidFormat(key)).isTrue();
            keys.add(key);
        }
        assertThat(keys).hasSize(1000);
        assertThat(PublicKeys.isValidFormat("pk_short")).isFalse();
        assertThat(PublicKeys.isValidFormat("ck_ABCDEFGHIJKLMNOPQRSTUVWX")).isFalse();
        assertThat(PublicKeys.isValidFormat("pk_ABCDEFGHIJKLMNOPQRSTUV-X")).isFalse();
        assertThat(PublicKeys.isValidFormat(null)).isFalse();
    }
}
