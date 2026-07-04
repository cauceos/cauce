package dev.cauce.channels.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelInboundMessage;
import dev.cauce.channels.spi.ChannelPayloadException;
import dev.cauce.channels.spi.WebhookRequest;
import dev.cauce.core.apikey.ApiKeyHasher;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TelegramChannelAdapterTest {

    /** Deterministic stand-in for the HMAC hasher: hash(x) = "hash:" + x. */
    private static final ApiKeyHasher FAKE_HASHER = new ApiKeyHasher() {
        @Override
        public String hash(String plaintext) {
            return "hash:" + plaintext;
        }

        @Override
        public boolean matches(String plaintext, String hash) {
            return hash(plaintext).equals(hash);
        }
    };

    private final TelegramChannelAdapter adapter = new TelegramChannelAdapter(FAKE_HASHER);

    private final ChannelConfig config = ChannelConfig.create(
            java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "telegram",
            "12345:bot-token", FAKE_HASHER.hash("s3cret"));

    @Test
    void channelType_always_isTelegram() {
        assertThat(adapter.channelType()).isEqualTo("telegram");
    }

    @Test
    void verify_matchingSecretHeader_passes() {
        WebhookRequest request = new WebhookRequest(
                Map.of("X-Telegram-Bot-Api-Secret-Token", "s3cret"), "{}");

        assertThat(adapter.verify(request, config)).isTrue();
    }

    @Test
    void verify_headerLookup_isCaseInsensitive() {
        WebhookRequest request = new WebhookRequest(
                Map.of("x-telegram-bot-api-secret-token", "s3cret"), "{}");

        assertThat(adapter.verify(request, config)).isTrue();
    }

    @Test
    void verify_wrongSecret_fails() {
        WebhookRequest request = new WebhookRequest(
                Map.of("X-Telegram-Bot-Api-Secret-Token", "wrong"), "{}");

        assertThat(adapter.verify(request, config)).isFalse();
    }

    @Test
    void verify_missingSecretHeader_fails() {
        assertThat(adapter.verify(new WebhookRequest(Map.of(), "{}"), config)).isFalse();
    }

    @Test
    void parse_textMessageUpdate_normalizesRefContentAndScopedKey() {
        String update = """
                {"update_id": 736294857,
                 "message": {"message_id": 42,
                             "from": {"id": 111, "first_name": "Ana"},
                             "chat": {"id": 987654321, "type": "private"},
                             "date": 1751623000,
                             "text": "Hola, quiero una cita"}}
                """;

        Optional<ChannelInboundMessage> parsed = adapter.parse(update, config);

        assertThat(parsed).hasValueSatisfying(message -> {
            assertThat(message.externalIdentityRef()).isEqualTo("987654321");
            assertThat(message.content()).isEqualTo("Hola, quiero una cita");
            assertThat(message.idempotencyKey()).isEqualTo(config.id() + ":736294857");
        });
    }

    @Test
    void parse_negativeGroupChatId_isPreserved() {
        String update = """
                {"update_id": 1, "message": {"chat": {"id": -100123456}, "text": "hi"}}
                """;

        assertThat(adapter.parse(update, config)).hasValueSatisfying(message ->
                assertThat(message.externalIdentityRef()).isEqualTo("-100123456"));
    }

    @Test
    void parse_editedMessageUpdate_isIgnored() {
        String update = """
                {"update_id": 2,
                 "edited_message": {"chat": {"id": 987654321}, "text": "edited"}}
                """;

        assertThat(adapter.parse(update, config)).isEmpty();
    }

    @Test
    void parse_messageWithoutText_isIgnored() {
        String update = """
                {"update_id": 3, "message": {"chat": {"id": 987654321}, "photo": []}}
                """;

        assertThat(adapter.parse(update, config)).isEmpty();
    }

    @Test
    void parse_malformedJson_throwsChannelPayloadException() {
        assertThatThrownBy(() -> adapter.parse("{not json", config))
                .isInstanceOf(ChannelPayloadException.class)
                .hasMessageContaining("Malformed");
    }

    @Test
    void parse_missingUpdateId_throwsChannelPayloadException() {
        assertThatThrownBy(() -> adapter.parse(
                "{\"message\": {\"chat\": {\"id\": 1}, \"text\": \"hi\"}}", config))
                .isInstanceOf(ChannelPayloadException.class)
                .hasMessageContaining("update_id");
    }
}
