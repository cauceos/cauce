package dev.cauce.channels.spi;

import dev.cauce.channels.config.ChannelConfig;

/**
 * The outbound half of the channel SPI (invariant 3): delivers a neutral
 * {@link ChannelOutboundMessage} through the provider (Telegram {@code sendMessage},
 * WhatsApp Cloud API {@code messages}). The credential travels in {@code config}.
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
}
