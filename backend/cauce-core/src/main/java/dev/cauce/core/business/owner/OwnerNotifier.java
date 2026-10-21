package dev.cauce.core.business.owner;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Port: sends a proactive message to a business owner through the channel they linked (ADR 0007,
 * B8, B9). Implemented by the channel layer. Synchronous, and it never throws for a delivery
 * problem: it says what happened, so the caller records it. A channel with a reply window
 * (WhatsApp's 24 hours, ADR 0006) refuses outside it with {@link Outcome#NOT_SENT} — no template
 * is sent instead.
 */
public interface OwnerNotifier {

    /** Sends {@code text} to {@code recipient}. Runs under the business's tenant context. */
    Delivery send(Recipient recipient, String text);

    /**
     * Where the owner reads: an identity on a channel instance, and when they linked it — the last
     * moment they are known to have written, if the channel has no later message from them.
     */
    record Recipient(UUID channelConfigId, UUID identityId, Instant linkedAt) {

        public Recipient {
            Objects.requireNonNull(channelConfigId, "channelConfigId");
            Objects.requireNonNull(identityId, "identityId");
            Objects.requireNonNull(linkedAt, "linkedAt");
        }
    }

    /** What happened to one send. */
    enum Outcome {
        /** The provider accepted the message. */
        SENT,
        /** Not sent, and sending again would not help (outside the reply window, no channel). */
        NOT_SENT,
        /** The send failed in a way a later attempt may not. */
        FAILED
    }

    /** The outcome and, unless SENT, a short reason for the record (never message content). */
    record Delivery(Outcome outcome, String reason) {

        public static final String OUTSIDE_REPLY_WINDOW = "outside_reply_window";
        public static final String CHANNEL_UNAVAILABLE = "channel_unavailable";

        public Delivery {
            Objects.requireNonNull(outcome, "outcome");
        }

        public static Delivery sent() {
            return new Delivery(Outcome.SENT, null);
        }

        public static Delivery notSent(String reason) {
            return new Delivery(Outcome.NOT_SENT, reason);
        }

        public static Delivery failed(String reason) {
            return new Delivery(Outcome.FAILED, reason);
        }
    }
}
