package dev.cauce.channels.telegram;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelInboundMessage;
import dev.cauce.channels.spi.ChannelPayloadException;
import dev.cauce.channels.spi.InboundChannelAdapter;
import dev.cauce.channels.spi.WebhookRequest;
import dev.cauce.core.apikey.ApiKeyHasher;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Inbound half of the Telegram channel: authenticates the webhook by the
 * {@code X-Telegram-Bot-Api-Secret-Token} header (the {@code secret_token} passed to
 * {@code setWebhook}, verified against the stored hash) and normalizes a Bot API
 * {@code Update} into the neutral inbound message.
 *
 * <p>Only plain text messages are ingested ({@code update.message.text}); every other
 * update shape (edited messages, callbacks, media without text, channel posts) is
 * authentic-but-unsupported and reported as empty, so the webhook acknowledges it and
 * Telegram does not redeliver.
 *
 * <p>Idempotency: Telegram redelivers an update with the <em>same</em> {@code update_id}
 * until it gets a 2xx, and {@code update_id} is sequential per bot — so
 * {@code configId + ":" + update_id} is the stable redelivery key ({@code message_id} is
 * per-chat and would collide across chats; the config prefix keeps two bots bound to the
 * same agent from colliding in the per-agent dedup table).
 *
 * <p>The outbound half ({@code sendMessage}) lands in the outbound commit.
 */
@Component
public class TelegramChannelAdapter implements InboundChannelAdapter {

    /** Header Telegram sends on every webhook request when a secret_token is set. */
    static final String SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    static final String CHANNEL_TYPE = "telegram";

    // Own mapper on purpose: the Telegram wire format is fixed by the Bot API and must not
    // inherit the application's Jackson configuration (e.g. the global snake_case strategy).
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApiKeyHasher hasher;

    public TelegramChannelAdapter(ApiKeyHasher hasher) {
        this.hasher = hasher;
    }

    @Override
    public String channelType() {
        return CHANNEL_TYPE;
    }

    @Override
    public boolean verify(WebhookRequest request, ChannelConfig config) {
        return request.header(SECRET_TOKEN_HEADER)
                .map(presented -> hasher.matches(presented, config.webhookSecretHash()))
                .orElse(false);
    }

    @Override
    public Optional<ChannelInboundMessage> parse(String body, ChannelConfig config) {
        JsonNode update;
        try {
            update = objectMapper.readTree(body);
        } catch (JacksonException e) {
            throw new ChannelPayloadException("Malformed Telegram update payload", e);
        }
        JsonNode updateId = update.path("update_id");
        if (!updateId.isIntegralNumber()) {
            throw new ChannelPayloadException("Telegram update payload has no update_id");
        }
        JsonNode message = update.path("message");
        JsonNode chatId = message.path("chat").path("id");
        JsonNode text = message.path("text");
        if (!chatId.isIntegralNumber() || !text.isTextual() || text.asText().isBlank()) {
            // Authentic but not an ingestable text message (edited_message, callback_query,
            // media without text, ...): acknowledge without ingesting.
            return Optional.empty();
        }
        return Optional.of(new ChannelInboundMessage(
                String.valueOf(chatId.asLong()),
                text.asText(),
                config.id() + ":" + updateId.asLong()));
    }
}
