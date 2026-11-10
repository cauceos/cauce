package dev.cauce.channels.spi;

import dev.cauce.channels.config.ChannelConfig;
import java.util.Set;

/**
 * The outbound half of the channel SPI (invariant 3): delivers a neutral
 * {@link ChannelOutboundMessage} through the provider (Telegram {@code sendMessage},
 * WhatsApp Cloud API {@code messages}). The credential travels in {@code config}. The content is
 * rendered for the channel's own formatting capability through {@link ReplyMarkdown}: shown as
 * formatting where the channel supports it, its markers dropped where it does not.
 *
 * <p>Contract only for now: no implementation or caller exists yet — delivery lands in the
 * outbound commit, where the trigger (an {@code InvocationCompleted} consumer or an
 * explicit port) is decided. This interface presumes neither: it receives fully resolved
 * data.
 */
public interface OutboundChannelAdapter {

    /** The channel type this adapter serves; see {@link InboundChannelAdapter#channelType()}. */
    String channelType();

    /**
     * Delivers {@code message} through the channel instance bound by {@code config}.
     *
     * @throws ChannelDeliveryException if the provider rejects or the delivery fails
     */
    void deliver(ChannelOutboundMessage message, ChannelConfig config);

    /**
     * The part types this channel carries besides text (ADR 0004, decision 3): none by default. A reply
     * with a part the channel does not carry gets that part rendered by the channel layer's declared
     * policy before delivery; for a product card, its name and price as text (ADR 0014, WC8).
     */
    default Set<String> carriedPartTypes() {
        return Set.of();
    }
}
