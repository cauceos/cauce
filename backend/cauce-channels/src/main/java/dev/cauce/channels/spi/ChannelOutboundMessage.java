package dev.cauce.channels.spi;

import java.util.Objects;

/**
 * An outbound channel message in neutral form: everything a channel needs to deliver an
 * agent reply, already resolved by the caller — the destination identity in the channel's
 * own vocabulary (the conversation's {@code external_identity_ref}) and the content.
 * Deliberately knows nothing about conversations, invocations, or events, so the future
 * delivery trigger (an event consumer or an explicit port) can build it from either path.
 */
public record ChannelOutboundMessage(String externalIdentityRef, String content) {

    public ChannelOutboundMessage {
        Objects.requireNonNull(externalIdentityRef, "externalIdentityRef must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (externalIdentityRef.isBlank()) {
            throw new IllegalArgumentException("externalIdentityRef must not be blank");
        }
    }
}
