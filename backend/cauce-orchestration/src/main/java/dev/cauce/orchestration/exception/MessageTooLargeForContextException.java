package dev.cauce.orchestration.exception;

/**
 * Thrown when the most recent message (the one that triggered the invocation) on its own
 * exceeds the effective context window for the target model, so no valid context can be
 * built. The message carries the offending estimate and the effective limit.
 */
public class MessageTooLargeForContextException extends RuntimeException {

    public MessageTooLargeForContextException(String message) {
        super(message);
    }
}
