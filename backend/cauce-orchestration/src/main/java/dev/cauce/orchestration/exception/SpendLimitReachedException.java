package dev.cauce.orchestration.exception;

/**
 * Thrown before an invocation calls the model when its client has spent its monthly limit (ADR
 * 0010, S6). Not a provider failure: the worker fails the invocation as {@code SPEND_LIMIT_REACHED},
 * the person gets the agent's fallback reply, and the owner was told when the limit was reached.
 */
public class SpendLimitReachedException extends RuntimeException {

    public SpendLimitReachedException(String message) {
        super(message);
    }
}
