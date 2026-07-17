package dev.cauce.api.invocation;

import dev.cauce.orchestration.PendingInvocationStatus;

/**
 * Public processing status of an invocation, decoupled from the internal
 * {@link PendingInvocationStatus} lifecycle so queue mechanics can evolve without breaking
 * the wire contract. The internal distinction between FAILED (unrecoverable error) and
 * ABANDONED (gave up after retries or a reaped claim) is a retry-mechanics detail: both
 * surface as {@link #FAILED}, and {@code failure_reason} carries the cause.
 */
public enum InvocationStatus {

    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    /** Maps the internal lifecycle status to the public vocabulary. */
    public static InvocationStatus from(PendingInvocationStatus status) {
        return switch (status) {
            case PENDING -> PENDING;
            case PROCESSING -> PROCESSING;
            case COMPLETED -> COMPLETED;
            case FAILED, ABANDONED -> FAILED;
        };
    }
}
