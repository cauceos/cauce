package dev.cauce.orchestration;

/**
 * Lifecycle status of a {@link PendingInvocation}.
 *
 * <p>{@link #PENDING} → {@link #PROCESSING} when a worker claims it. From PROCESSING it
 * either succeeds ({@link #COMPLETED}), is released back to {@link #PENDING} for another
 * attempt, fails unrecoverably ({@link #FAILED}), or is given up after exhausting its
 * attempt budget ({@link #ABANDONED}). COMPLETED, FAILED and ABANDONED are absorbing
 * states: no transition leaves them.
 */
public enum PendingInvocationStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    ABANDONED
}
