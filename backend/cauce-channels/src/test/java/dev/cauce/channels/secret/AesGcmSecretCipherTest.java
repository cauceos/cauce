package dev.cauce.channels.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesGcmSecretCipherTest {

    private final AesGcmSecretCipher cipher = new AesGcmSecretCipher(SecretEncryptionProperties.DEV_ONLY_KEY);

    @Test
    void sealThenOpen_roundTrips_andTheSealedFormHidesThePlaintext() {
        String sealed = cipher.seal("EAAG-access-token-ñ");

        assertThat(sealed).startsWith("v1:").doesNotContain("access-token");
        assertThat(cipher.isSealed(sealed)).isTrue();
        assertThat(cipher.open(sealed)).isEqualTo("EAAG-access-token-ñ");
    }

    @Test
    void seal_usesAFreshIvEachTime() {
        assertThat(cipher.seal("same")).isNotEqualTo(cipher.seal("same"));
    }

    @Test
    void open_aTamperedValue_fails_neverReturnsGarbage() {
        String sealed = cipher.seal("secret");
        byte[] packed = Base64.getDecoder().decode(sealed.substring(3));
        packed[packed.length - 1] ^= 0x01;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(packed);

        assertThatThrownBy(() -> cipher.open(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void open_underAnotherKey_fails() {
        String sealed = cipher.seal("secret");
        AesGcmSecretCipher other = new AesGcmSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));

        assertThatThrownBy(() -> other.open(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void open_aPlaintextValue_isRejected() {
        assertThat(cipher.isSealed("12345:bot-token")).isFalse();
        assertThatThrownBy(() -> cipher.open("12345:bot-token")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void construction_withAMissingOrMalformedKey_failsAtStartup() {
        assertThatThrownBy(() -> new AesGcmSecretCipher(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmSecretCipher("not base64!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmSecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
