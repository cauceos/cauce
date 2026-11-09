package dev.cauce.tenancy;

import java.time.Duration;
import java.util.Objects;

/**
 * What {@link ConversationService#closeIfInactive} found (ADR 0013, T2): the thread is still open,
 * this call closed it for inactivity, or it was no longer open (a concurrent message closed it). In
 * the last two cases the caller resolves the OPEN thread again.
 */
public sealed interface InactivityCheck {

    /** The thread is OPEN and within its agent's window, or the agent never closes threads. */
    record StillOpen() implements InactivityCheck {
    }

    /** The thread was not OPEN when checked or closed: someone else closed it. */
    record NoLongerOpen() implements InactivityCheck {
    }

    /**
     * This call closed the thread.
     *
     * @param idle how long it had gone without a conversational message, by the database clock
     * @param windowHours the agent's window it exceeded
     */
    record Closed(Duration idle, int windowHours) implements InactivityCheck {
        public Closed {
            Objects.requireNonNull(idle, "idle");
        }
    }
}
