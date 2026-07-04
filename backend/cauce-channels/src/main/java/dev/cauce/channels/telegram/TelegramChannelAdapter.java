package dev.cauce.channels.telegram;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelInboundMessage;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.ChannelPayloadException;
import dev.cauce.channels.spi.InboundChannelAdapter;
import dev.cauce.channels.spi.OutboundChannelAdapter;
import dev.cauce.channels.spi.WebhookRequest;
import dev.cauce.core.apikey.ApiKeyHasher;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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
 * <p>The outbound half delivers via {@code sendMessage}: {@code POST
 * {base-url}/bot{token}/sendMessage} with the conversation's {@code external_identity_ref}
 * as {@code chat_id}. The bot token is the config's credential; failures surface as
 * {@link dev.cauce.channels.spi.ChannelDeliveryException} for the (best-effort) caller.
 * Registered as a bean by {@code ChannelsConfig} (it carries the HTTP client and
 * properties, mirroring the LLM adapter configurations).
 */
public class TelegramChannelAdapter implements InboundChannelAdapter, OutboundChannelAdapter {

    /** Header Telegram sends on every webhook request when a secret_token is set. */
    static final String SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    static final String CHANNEL_TYPE = "telegram";

    // Own mapper on purpose: the Telegram wire format is fixed by the Bot API and must not
    // inherit the application's Jackson configuration (e.g. the global snake_case strategy).
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApiKeyHasher hasher;
    private final HttpClient httpClient;
    private final TelegramProperties properties;

    public TelegramChannelAdapter(ApiKeyHasher hasher, HttpClient httpClient,
                                  TelegramProperties properties) {
        this.hasher = hasher;
        this.httpClient = httpClient;
        this.properties = properties;
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

    @Override
    public void deliver(ChannelOutboundMessage message, ChannelConfig config) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("chat_id", message.externalIdentityRef());
        payload.put("text", message.content());

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(properties.getBaseUrl() + "/bot" + config.credential() + "/sendMessage"))
                .timeout(properties.getTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ChannelDeliveryException("HTTP call to Telegram failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelDeliveryException("Interrupted during Telegram delivery", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // The response body carries Telegram's error description, never our token.
            throw new ChannelDeliveryException("Telegram sendMessage returned HTTP "
                    + response.statusCode() + ": " + response.body());
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new ChannelDeliveryException("Malformed Telegram sendMessage response", e);
        }
        if (!body.path("ok").asBoolean(false)) {
            throw new ChannelDeliveryException("Telegram sendMessage rejected: "
                    + body.path("description").asText("(no description)"));
        }
    }
}
