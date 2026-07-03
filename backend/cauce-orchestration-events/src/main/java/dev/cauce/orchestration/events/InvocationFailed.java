package dev.cauce.orchestration.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The invocation reached a permanent failure state (queue row FAILED or ABANDONED) and no
 * agent reply will be produced for its trigger message. Emitted by the worker or the reaper
 * after the terminal transition has committed. A retry release (backoff, budget remaining)
 * is not permanent and emits nothing.
 *
 * @param invocationId the invocation that failed
 * @param failureType which permanent-failure path was taken
 * @param detail human-readable failure summary (bounded by the queue's stored error length)
 * @param occurredAt when the terminal transition was recorded
 */
public record InvocationFailed(
        UUID invocationId,
        InvocationFailureType failureType,
        String detail,
        Instant occurredAt) implements OrchestrationEvent {

    public InvocationFailed {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(failureType, "failureType");
        EventPreconditions.requireText(detail, "detail");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
