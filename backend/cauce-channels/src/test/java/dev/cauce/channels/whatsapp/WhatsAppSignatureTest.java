package dev.cauce.channels.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WhatsAppSignatureTest {

    private static final byte[] BODY = "{\"object\":\"whatsapp_business_account\",\"x\":\"é\"}"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void matches_aSignatureComputedOverTheRawBodyWithTheAppSecret() {
        String header = WhatsAppSignature.sign(BODY, "app-secret");

        assertThat(header).startsWith("sha256=");
        assertThat(WhatsAppSignature.matches(header, BODY, "app-secret")).isTrue();
        assertThat(WhatsAppSignature.matches(header.toUpperCase().replace("SHA256=", "sha256="), BODY,
                "app-secret")).isTrue();
    }

    @Test
    void rejects_aWrongSecret_aTamperedBody_andMalformedHeaders() {
        String header = WhatsAppSignature.sign(BODY, "app-secret");
        byte[] tampered = BODY.clone();
        tampered[tampered.length - 2] ^= 0x01;

        assertThat(WhatsAppSignature.matches(header, BODY, "other-secret")).isFalse();
        assertThat(WhatsAppSignature.matches(header, tampered, "app-secret")).isFalse();
        assertThat(WhatsAppSignature.matches(header.substring(7), BODY, "app-secret")).isFalse();
        assertThat(WhatsAppSignature.matches("sha256=not-hex", BODY, "app-secret")).isFalse();
        assertThat(WhatsAppSignature.matches(null, BODY, "app-secret")).isFalse();
    }
}
