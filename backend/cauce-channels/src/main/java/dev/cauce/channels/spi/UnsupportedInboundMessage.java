package dev.cauce.channels.spi;

import dev.cauce.core.identity.IdentityKind;
import java.util.Objects;

/**
 * An authentic message from a person that the channel delivered but Cauce does not read yet (a
 * Telegram voice note, a photo without a caption): never ingested, never silence (ADR 0006, W5).
 * The webhook flow audits it once and answers {@code reply}, the adapter's fixed text.
 *
 * @param identityKind what {@code identityValue} is
 * @param identityValue the sender, as the adapter would name it for an ingest, to answer
 * @param messageType the channel's own name for what arrived ({@code voice}, {@code photo}...),
 *        audited as is
 * @param providerMessageId the provider's stable id of the delivery, which makes a redelivery
 *        a no-op
 * @param reply the fixed text that answers it
 */
public record UnsupportedInboundMessage(IdentityKind identityKind, String identityValue, String messageType,
                                        String providerMessageId, String reply) {

    public UnsupportedInboundMessage {
        Objects.requireNonNull(identityKind, "identityKind must not be null");
        requireText(identityValue, "identityValue");
        requireText(messageType, "messageType");
        requireText(providerMessageId, "providerMessageId");
        requireText(reply, "reply");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
