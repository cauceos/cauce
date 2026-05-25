package dev.cauce.orchestration.exception;

/**
 * Thrown by {@link dev.cauce.orchestration.PendingInvocation#releaseForRetry(String)} when
 * the invocation has already used its full attempt budget. The caller must instead
 * terminate the invocation with {@code fail(...)} or {@code abandon(...)}.
 */
public class MaxRetriesExceededException extends RuntimeException {

    public MaxRetriesExceededException(String message) {
        super(message);
    }
}
