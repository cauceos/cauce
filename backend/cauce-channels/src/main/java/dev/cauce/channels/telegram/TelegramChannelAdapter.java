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
import dev.cauce.channels.spi.ReplyMarkdown;
import dev.cauce.channels.spi.UnsupportedInterlocutorException;
import dev.cauce.channels.spi.WebhookRequest;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.identity.IdentityKind;
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
 * <p>Only private chats are accepted. A group's {@code chat.id} is not a person and there is
 * no identity kind for it (ADR 0004, conscious limitation), so a text message from any chat
 * whose {@code type} is not {@code private} — including one that omits the type — fails
 * loudly with {@link UnsupportedInterlocutorException} instead of minting a person-kind
 * identity for the group.
 *
 * <p>Idempotency: Telegram redelivers an update with the <em>same</em> {@code update_id}
 * until it gets a 2xx, and {@code update_id} is sequential per bot — so
 * {@code configId + ":" + update_id} is the stable redelivery key ({@code message_id} is
 * per-chat and would collide across chats; the config prefix keeps two bots bound to the
 * same agent from colliding in the per-agent dedup table).
 *
 * <p>The outbound half delivers via {@code sendMessage}: {@code POST
 * {base-url}/bot{token}/sendMessage} with the interlocutor's identity value (the
 * {@code chat.id} this adapter produced) as {@code chat_id}. The bot token is the config's credential; failures surface as
 * {@link dev.cauce.channels.spi.ChannelDeliveryException} for the (best-effort) caller.
 * Telegram shows formatting only when asked: the agent's Markdown is rendered to Telegram's HTML
 * subset and sent with {@code parse_mode=HTML}, so a code never arrives as {@code **9PNVXQ**}.
 * Should Telegram still reject the entities, the same reply is sent once more as plain text,
 * markers dropped: formatting is never worth losing the reply.
 * Registered as a bean by {@code ChannelsConfig} (it carries the HTTP client and
 * properties, mirroring the LLM adapter configurations).
 */
public class TelegramChannelAdapter implements InboundChannelAdapter, OutboundChannelAdapter {

    /** Header Telegram sends on every webhook request when a secret_token is set. */
    static final String SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    static final String CHANNEL_TYPE = "telegram";

    /** What Telegram answers (HTTP 400) to a {@code parse_mode} text it cannot parse. */
    static final String ENTITIES_REJECTED = "can't parse entities";

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
        String chatType = message.path("chat").path("type").asText("");
        if (!"private".equals(chatType)) {
            throw new UnsupportedInterlocutorException("Telegram chat type '" + chatType
                    + "' is not a person; only private chats are supported");
        }
        // In a private chat, chat.id is the user's id. Named PROVIDER_USER_ID because it is
        // minted by Telegram and meaningful only within it.
        return Optional.of(new ChannelInboundMessage(
                IdentityKind.PROVIDER_USER_ID,
                String.valueOf(chatId.asLong()),
                text.asText(),
                config.id() + ":" + updateId.asLong()));
    }

    @Override
    public void deliver(ChannelOutboundMessage message, ChannelConfig config) {
        ObjectNode payload = objectMapper.createObjectNode();
        if (message.identityKind() != IdentityKind.PROVIDER_USER_ID) {
            throw new ChannelDeliveryException("Telegram can only deliver to a PROVIDER_USER_ID, not "
                    + message.identityKind());
        }
        payload.put("chat_id", message.identityValue());
        payload.put("text", ReplyMarkdown.toHtml(message.content()));
        payload.put("parse_mode", "HTML");
        try {
            send(payload, config);
        } catch (EntitiesRejectedException e) {
            ObjectNode plain = objectMapper.createObjectNode();
            plain.put("chat_id", message.identityValue());
            plain.put("text", ReplyMarkdown.toPlain(message.content()));
            send(plain, config);
        }
    }

    private void send(ObjectNode payload, ChannelConfig config) {
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
        if (response.statusCode() == 400 && payload.has("parse_mode")
                && response.body().contains(ENTITIES_REJECTED)) {
            throw new EntitiesRejectedException();
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

    /** Telegram refused the formatting of a reply, not the reply: it is sent again as plain text. */
    private static final class EntitiesRejectedException extends RuntimeException {

        EntitiesRejectedException() {
            super(null, null, false, false);
        }
    }
}
