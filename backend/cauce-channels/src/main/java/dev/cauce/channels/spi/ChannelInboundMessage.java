package dev.cauce.channels.spi;

import java.util.Objects;

/**
 * An inbound channel message in neutral form — the boundary between a provider payload and
 * the ingest unit. The webhook flow translates this record into an
 * {@code InboundMessageService.ingest} call; adapters never touch conversations or
 * messages directly.
 *
 * @param externalIdentityRef the external user's identity in the channel's own vocabulary
 *        (Telegram: {@code chat.id}; WhatsApp: the sender's phone number). Opaque here.
 * @param content the textual message content
 * @param idempotencyKey a key stable across provider redeliveries (Telegram:
 *        {@code update_id}; WhatsApp: {@code wamid}), scoped by the adapter so distinct
 *        channel instances cannot collide. Feeds the ingest deduplication (V15).
 */
public record ChannelInboundMessage(String externalIdentityRef, String content,
                                    String idempotencyKey) {

    public ChannelInboundMessage {
        requireText(externalIdentityRef, "externalIdentityRef");
        requireText(content, "content");
        requireText(idempotencyKey, "idempotencyKey");
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
