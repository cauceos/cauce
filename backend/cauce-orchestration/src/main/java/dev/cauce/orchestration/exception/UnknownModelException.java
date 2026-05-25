package dev.cauce.orchestration.exception;

/**
 * Thrown when a model name has no registered context window in
 * {@link dev.cauce.orchestration.context.ModelContextWindow}. The known-model table is
 * hardcoded for now (Anthropic only, the single functional adapter); unknown models are
 * rejected rather than silently assigned a default window.
 */
public class UnknownModelException extends RuntimeException {

    public UnknownModelException(String message) {
        super(message);
    }
}
