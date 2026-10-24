package dev.cauce.channels.spi;

import dev.cauce.channels.config.ChannelConfig;

/**
 * The media half of the channel SPI (ADR 0012, V1): hands over the bytes of a voice note a person
 * sent, by the provider's opaque reference the inbound half put in its audio part. Implemented by
 * the adapters of channels that carry voice notes; the bytes are read once, for the transcription,
 * and never stored.
 */
public interface MediaChannelAdapter {

    /** The channel type this adapter serves, as {@link InboundChannelAdapter#channelType()}. */
    String channelType();

    /**
     * The bytes {@code providerReference} names, fetched with {@code config}'s credential; never
     * more than {@code maxBytes}.
     *
     * @throws dev.cauce.orchestration.exception.VoiceNoteRejectedException if they are larger
     * @throws ChannelMediaException if the provider does not hand them over
     */
    byte[] download(String providerReference, ChannelConfig config, long maxBytes);
}
