package dev.cauce.orchestration.exception;

/**
 * Thrown when a {@link dev.cauce.orchestration.PendingInvocation} lifecycle transition is
 * not allowed from the current status (e.g. claiming a row that is not PENDING, or any
 * transition out of an absorbing COMPLETED/FAILED/ABANDONED state).
 */
public class InvalidPendingInvocationTransitionException extends RuntimeException {

    public InvalidPendingInvocationTransitionException(String message) {
        super(message);
    }
}
