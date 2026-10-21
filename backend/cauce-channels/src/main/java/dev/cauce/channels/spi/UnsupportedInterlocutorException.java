package dev.cauce.channels.spi;

/**
 * Thrown by {@link InboundChannelAdapter#parse} for an authentic update whose sender cannot be
 * named with any {@link dev.cauce.core.identity.IdentityKind} — the conscious limitation of
 * ADR 0004's identity unit: a Telegram group's {@code chat.id} is not a person, and there is no
 * {@code GROUP} kind. The adapter fails loudly instead of minting a person-kind identity for it.
 *
 * <p>Distinct from {@link ChannelPayloadException} (a malformed payload): the request is
 * authentic and well formed, so the webhook flow records the refusal with its reason and still
 * acknowledges it, because a non-2xx would make the provider redeliver an update that can
 * never be accepted.
 */
public class UnsupportedInterlocutorException extends RuntimeException {

    public UnsupportedInterlocutorException(String message) {
        super(message);
    }
}
