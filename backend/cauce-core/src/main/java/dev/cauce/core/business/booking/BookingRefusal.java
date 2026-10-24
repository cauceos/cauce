package dev.cauce.core.business.booking;

import java.util.Objects;

/**
 * Why a booking action was refused (ADR 0007, B6): a stable reason the agent can act on, and a
 * message written for it. A refusal is an answer, not a failure — the agent explains it to the
 * person and offers what is possible.
 */
public record BookingRefusal(Reason reason, String message) {

    /** The stable reasons. */
    public enum Reason {
        /** The arguments do not describe a booking (a bad date, a blank name). */
        INVALID,
        /** The date is closed, or the shift does not run that day. */
        CLOSED,
        /** The date is before today, or the shift has already started. */
        PAST,
        /** The date is beyond how far ahead the business books. */
        TOO_FAR,
        /** Not enough capacity remains. */
        FULL,
        /** The party is larger than the business books without a human: hand it to the owner. */
        PARTY_TOO_LARGE,
        /** No such booking for this person. */
        NOT_FOUND,
        /** The booking has nothing pending to confirm. */
        NOT_PENDING,
        /** The proposal expired before it was confirmed: propose again. */
        EXPIRED,
        /** A proposal is confirmed in the same turn it was made: ask the person first. */
        SAME_TURN
    }

    public BookingRefusal {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");
    }

    /** As an exception, for an adapter whose operations return the booking they act on. */
    public BookingRefusedException toException() {
        return new BookingRefusedException(this);
    }
}
