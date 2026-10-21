package dev.cauce.channels.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookRequestTest {

    @Test
    void rawBody_isExactlyTheReceivedBytes_evenWhenTheyAreNotValidUtf8() {
        // A signature is computed over these bytes; decoding and re-encoding would change them.
        byte[] received = {'{', (byte) 0xFF, (byte) 0xFE, '}'};

        WebhookRequest request = new WebhookRequest(Map.of(), received);

        assertThat(request.rawBody()).containsExactly(received);
        assertThat(request.body().getBytes(StandardCharsets.UTF_8)).isNotEqualTo(received);
    }

    @Test
    void rawBody_isCopiedInAndOut() {
        byte[] received = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        WebhookRequest request = new WebhookRequest(Map.of(), received);

        received[0] = 'x';
        request.rawBody()[1] = 'y';

        assertThat(request.body()).isEqualTo("{\"a\":1}");
    }

    @Test
    void stringConstructor_encodesUtf8_andBodyDecodesIt() {
        WebhookRequest request = new WebhookRequest(Map.of(), "café");

        assertThat(request.rawBody()).containsExactly("café".getBytes(StandardCharsets.UTF_8));
        assertThat(request.body()).isEqualTo("café");
    }

    @Test
    void header_isCaseInsensitive_andToStringOmitsBodyAndHeaderValues() {
        WebhookRequest request = new WebhookRequest(Map.of("X-Hub-Signature-256", "sha256=abc"), "secret body");

        assertThat(request.header("x-hub-signature-256")).contains("sha256=abc");
        assertThat(request.toString()).doesNotContain("secret body").doesNotContain("sha256=abc");
    }
}
