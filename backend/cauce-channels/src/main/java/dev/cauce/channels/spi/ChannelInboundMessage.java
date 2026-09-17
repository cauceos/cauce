package dev.cauce.channels.spi;

import dev.cauce.core.identity.IdentityKind;
import java.util.Objects;

/**
 * An inbound channel message in neutral form — the boundary between a provider payload and
 * the ingest unit. The webhook flow translates this record into an
 * {@code InboundMessageService.ingest} call; adapters never touch conversations or
 * messages directly.
 *
 * <p>The interlocutor is identified by a typed pair (ADR 0004, decision 1): the adapter that
 * produced the value is the one that knows what it is, so it names the {@code kind} from the
 * closed domain vocabulary and hands the value over untouched. The core stores and compares;
 * it does not interpret, so any normalisation a channel needs (a phone number's spelling)
 * happens here, in the adapter, before the record is built.
 *
 * @param identityKind what {@code identityValue} is (Telegram: {@link IdentityKind#PROVIDER_USER_ID}
 *        for {@code chat.id}; WhatsApp: {@link IdentityKind#PHONE_NUMBER} for the sender)
 * @param identityValue the interlocutor's identifier in the channel's own vocabulary
 * @param content the textual message content
 * @param idempotencyKey a key stable across provider redeliveries (Telegram:
 *        {@code update_id}; WhatsApp: {@code wamid}), scoped by the adapter so distinct
 *        channel instances cannot collide. Feeds the ingest deduplication (V15).
 */
public record ChannelInboundMessage(IdentityKind identityKind, String identityValue, String content,
                                    String idempotencyKey) {

    public ChannelInboundMessage {
        Objects.requireNonNull(identityKind, "identityKind must not be null");
        requireText(identityValue, "identityValue");
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
