package dev.cauce.channels.spi;

import dev.cauce.core.identity.IdentityKind;
import java.util.Objects;

/**
 * An outbound channel message in neutral form: everything a channel needs to deliver an
 * agent reply, already resolved by the caller. The destination is the conversation's
 * interlocutor as the same typed pair the inbound half produced (ADR 0004, decision 1): the
 * adapter reads the {@code identityValue} it once handed over, and the {@code identityKind}
 * tells it what that value is, so an adapter serving several kinds (WhatsApp: a phone number
 * or a provider id) never has to guess. Deliberately knows nothing about conversations,
 * invocations, or events.
 *
 * @param identityKind what {@code identityValue} is
 * @param identityValue the interlocutor's identifier in the channel's own vocabulary, exactly
 *        as the inbound adapter produced it (Telegram: the {@code chat.id})
 * @param content the textual reply
 */
public record ChannelOutboundMessage(IdentityKind identityKind, String identityValue, String content) {

    public ChannelOutboundMessage {
        Objects.requireNonNull(identityKind, "identityKind must not be null");
        Objects.requireNonNull(identityValue, "identityValue must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (identityValue.isBlank()) {
            throw new IllegalArgumentException("identityValue must not be blank");
        }
    }
}
