package dev.cauce.orchestration.exception;

/**
 * Thrown when the agentic loop reaches its hard iteration cap without the model producing a
 * final (tool-free) reply — typically an agent looping on a tool it cannot satisfy. It is not a
 * provider failure, so the worker fails the invocation (a non-retryable, non-LLM error) after
 * the orchestrator has recorded a SYSTEM error message in the conversation.
 */
public class MaxToolIterationsExceededException extends RuntimeException {

    public MaxToolIterationsExceededException(String message) {
        super(message);
    }
}
