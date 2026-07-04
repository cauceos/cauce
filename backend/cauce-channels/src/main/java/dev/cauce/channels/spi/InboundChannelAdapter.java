package dev.cauce.channels.spi;

import dev.cauce.channels.config.ChannelConfig;
import java.util.Optional;

/**
 * The inbound half of the channel SPI (invariant 3): turns a provider webhook request into
 * a neutral {@link ChannelInboundMessage}. One implementation per channel type, registered
 * as a Spring bean and collected by {@link ChannelAdapterRegistry} — mirroring the
 * {@code LlmProvider} pattern. Adapters are pure translators: they never touch
 * conversations, tenants, or the ingest unit; the webhook flow drives them.
 */
public interface InboundChannelAdapter {

    /**
     * The channel type this adapter serves (e.g. {@code "telegram"}), the value stamped
     * on conversations as {@code channel_type}. Stable, lowercase, unique across adapters.
     */
    String channelType();

    /**
     * Authenticates {@code request} against {@code config}. Telegram compares the
     * {@code X-Telegram-Bot-Api-Secret-Token} header with the config's stored secret;
     * WhatsApp verifies the {@code X-Hub-Signature-256} HMAC over the raw body — the
     * neutral request carries headers and unparsed body to support both. A request that
     * fails verification must never be processed.
     */
    boolean verify(WebhookRequest request, ChannelConfig config);

    /**
     * Normalizes the provider payload into a neutral inbound message. Empty means the
     * payload is authentic but not an ingestable user message (Telegram: edited messages,
     * callbacks, non-text updates; WhatsApp: status notifications) — the webhook answers
     * 2xx without ingesting, so the provider does not redeliver.
     *
     * @throws ChannelPayloadException if the payload is malformed for this channel
     */
    Optional<ChannelInboundMessage> parse(String body, ChannelConfig config);
}
