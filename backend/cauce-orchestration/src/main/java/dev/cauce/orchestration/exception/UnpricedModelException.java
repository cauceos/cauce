package dev.cauce.orchestration.exception;

/**
 * Thrown before an invocation calls the model when its client has a spend limit and the agent's
 * model has no price in force (ADR 0010, S7): a limit that cannot price what it limits would be no
 * limit. The worker fails the invocation as {@code AGENT_CONFIGURATION}; adding the model's price,
 * or changing the agent's model, is the fix.
 */
public class UnpricedModelException extends RuntimeException {

    public UnpricedModelException(String message) {
        super(message);
    }
}
