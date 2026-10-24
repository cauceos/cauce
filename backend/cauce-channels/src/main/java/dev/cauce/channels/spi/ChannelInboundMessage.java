package dev.cauce.channels.spi;

import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.message.MessagePart;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
 * @param parts what the message carries, in order (ADR 0004, decision 2): one text part for a
 *        text message; an audio part for a voice note, as the channel announced it (the worker
 *        fetches and transcribes it, ADR 0012)
 * @param idempotencyKey a key stable across provider redeliveries (Telegram:
 *        {@code update_id}; WhatsApp: {@code wamid}), scoped by the adapter so distinct
 *        channel instances cannot collide. Feeds the ingest deduplication (V15).
 */
public record ChannelInboundMessage(IdentityKind identityKind, String identityValue, List<MessagePart> parts,
                                    String idempotencyKey) {

    public ChannelInboundMessage {
        Objects.requireNonNull(identityKind, "identityKind must not be null");
        requireText(identityValue, "identityValue");
        Objects.requireNonNull(parts, "parts must not be null");
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("parts must not be empty");
        }
        parts = List.copyOf(parts);
        requireText(idempotencyKey, "idempotencyKey");
    }

    /** A text message: one text part. */
    public ChannelInboundMessage(IdentityKind identityKind, String identityValue, String content,
                                 String idempotencyKey) {
        this(identityKind, identityValue, List.of(new MessagePart.Text(requireText(content, "content"))),
                idempotencyKey);
    }

    /** The message's text, its text parts one per line; empty for a message with none (a voice note). */
    public String content() {
        return parts.stream().filter(MessagePart.Text.class::isInstance).map(part -> ((MessagePart.Text) part).text())
                .collect(Collectors.joining("\n"));
    }

    /** Whether it carries a voice note, which the instance can read only with a transcriber. */
    public boolean hasAudio() {
        return parts.stream().anyMatch(MessagePart.Audio.class::isInstance);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
