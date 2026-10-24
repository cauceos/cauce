package dev.cauce.channels.telegram;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelInboundMessage;
import dev.cauce.channels.spi.ChannelMediaException;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.ChannelPayloadException;
import dev.cauce.channels.spi.InboundChannelAdapter;
import dev.cauce.channels.spi.MediaChannelAdapter;
import dev.cauce.channels.spi.OutboundChannelAdapter;
import dev.cauce.channels.spi.ReplyMarkdown;
import dev.cauce.channels.spi.UnsupportedInboundMessage;
import dev.cauce.channels.spi.UnsupportedInterlocutorException;
import dev.cauce.channels.spi.WebhookRequest;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.message.MessagePart;
import dev.cauce.orchestration.exception.VoiceNoteRejectedException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * Telegram does not redeliver. A private message without text — a voice note, a photo — is a
 * person waiting for an answer: {@link #unsupported} names it, so that it is answered with a fixed
 * text instead of silence (ADR 0006, W5).
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
public class TelegramChannelAdapter implements InboundChannelAdapter, OutboundChannelAdapter, MediaChannelAdapter {

    /** Header Telegram sends on every webhook request when a secret_token is set. */
    static final String SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    static final String CHANNEL_TYPE = "telegram";

    /** What Telegram answers (HTTP 400) to a {@code parse_mode} text it cannot parse. */
    static final String ENTITIES_REJECTED = "can't parse entities";

    /** The Bot API's fields for what a message carries instead of text, as its audited type. */
    static final List<String> MEDIA_FIELDS = List.of("voice", "audio", "video_note", "video", "photo",
            "document", "sticker", "animation", "location", "venue", "contact", "poll", "dice");

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
        boolean isText = text.isTextual() && !text.asText().isBlank();
        boolean isVoice = message.path("voice").path("file_id").isTextual()
                && !message.path("voice").path("file_id").asText().isBlank();
        if (!chatId.isIntegralNumber() || (!isText && !isVoice)) {
            // Authentic but not an ingestable text message or voice note (edited_message,
            // callback_query, other media, ...): acknowledge without ingesting.
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
                isText ? List.of(new MessagePart.Text(text.asText())) : voiceNote(message),
                config.id() + ":" + updateId.asLong()));
    }

    @Override
    public Optional<UnsupportedInboundMessage> unsupported(String body, ChannelConfig config) {
        JsonNode update;
        try {
            update = objectMapper.readTree(body);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        JsonNode updateId = update.path("update_id");
        JsonNode message = update.path("message");
        JsonNode chatId = message.path("chat").path("id");
        JsonNode text = message.path("text");
        if (!updateId.isIntegralNumber() || !chatId.isIntegralNumber()
                || !"private".equals(message.path("chat").path("type").asText(""))
                || (text.isTextual() && !text.asText().isBlank())) {
            return Optional.empty(); // not a person's message, or text that parse() ingests
        }
        return Optional.of(new UnsupportedInboundMessage(IdentityKind.PROVIDER_USER_ID,
                String.valueOf(chatId.asLong()), messageType(message), String.valueOf(updateId.asLong()),
                properties.getUnsupportedMessageReply()));
    }

    /**
     * A voice note as Telegram announces it (ADR 0012): Ogg Opus by default, its {@code file_id} the
     * reference {@link #download} fetches it by, with its size and length when given; a caption is
     * a text part after it.
     */
    private static List<MessagePart> voiceNote(JsonNode message) {
        JsonNode voice = message.path("voice");
        MessagePart.Audio note = MessagePart.Audio.received(voice.path("mime_type").asText("audio/ogg"),
                voice.path("file_id").asText(),
                voice.path("file_size").isIntegralNumber() ? voice.path("file_size").asLong() : null,
                voice.path("duration").isIntegralNumber() ? voice.path("duration").asInt() : null);
        JsonNode caption = message.path("caption");
        return caption.isTextual() && !caption.asText().isBlank()
                ? List.of(note, new MessagePart.Text(caption.asText()))
                : List.of(note);
    }

    /**
     * The bytes of a voice note (ADR 0012, V1): {@code getFile} names its path on Telegram's file
     * server and its size, then the file is read from there, both with the bot token. Never more
     * than {@code maxBytes}: a bot cannot download more than 20 MB anyway.
     */
    @Override
    public byte[] download(String providerReference, ChannelConfig config, long maxBytes) {
        HttpResponse<String> located = get(URI.create(properties.getBaseUrl() + "/bot" + config.credential()
                + "/getFile?file_id=" + URLEncoder.encode(providerReference, StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode file;
        try {
            file = objectMapper.readTree(located.body()).path("result");
        } catch (JacksonException e) {
            throw new ChannelMediaException("Telegram getFile returned a body that is not JSON", e);
        }
        if (located.statusCode() != 200 || !file.path("file_path").isTextual()) {
            // The body carries Telegram's error description, never our token.
            throw new ChannelMediaException("Telegram getFile returned HTTP " + located.statusCode() + ": "
                    + located.body());
        }
        if (file.path("file_size").isIntegralNumber() && file.path("file_size").asLong() > maxBytes) {
            throw new VoiceNoteRejectedException("The voice note is " + file.path("file_size").asLong()
                    + " bytes, over the limit of " + maxBytes);
        }
        HttpResponse<byte[]> bytes = get(URI.create(properties.getBaseUrl() + "/file/bot" + config.credential()
                + "/" + file.path("file_path").asText()), HttpResponse.BodyHandlers.ofByteArray());
        if (bytes.statusCode() != 200) {
            throw new ChannelMediaException("Telegram file download returned HTTP " + bytes.statusCode());
        }
        if (bytes.body().length > maxBytes) {
            throw new VoiceNoteRejectedException("The voice note is " + bytes.body().length
                    + " bytes, over the limit of " + maxBytes);
        }
        return bytes.body();
    }

    private <T> HttpResponse<T> get(URI uri, HttpResponse.BodyHandler<T> handler) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(properties.getTimeout()).GET().build();
        try {
            return httpClient.send(request, handler);
        } catch (IOException e) {
            // The URI holds the token: never in the message.
            throw new ChannelMediaException("HTTP call to Telegram for a voice note failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelMediaException("Interrupted while fetching a voice note from Telegram", e);
        }
    }

    /** What a message without text carries, by the Bot API's field for it; {@code other} if none is known. */
    private static String messageType(JsonNode message) {
        return MEDIA_FIELDS.stream().filter(message::has).findFirst().orElse("other");
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
