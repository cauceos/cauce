package dev.cauce.channels.spi;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.core.identity.IdentityKind;

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

    /**
     * Shows the interlocutor, in the channel's own way, that someone is typing to them — never a
     * message. Lasts a few seconds, so the caller repeats it while the note is heard. The default
     * shows nothing: a channel whose indicator is not addressed by the interlocutor (WhatsApp ties it
     * to the inbound message) shows it on its own when the note arrives.
     *
     * @throws ChannelDeliveryException if the provider refuses it; the caller only logs it
     */
    default void showTyping(ChannelConfig config, IdentityKind identityKind, String identityValue) {
    }
}
