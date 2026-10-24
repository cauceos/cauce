package dev.cauce.orchestration.exception;

/**
 * Thrown before an invocation calls the model when the agent's system prompt and tool schemas,
 * with the current message, do not fit the context window the provider serves: sent anyway, the
 * provider would cut the prompt silently. The worker fails the invocation as
 * {@code AGENT_CONFIGURATION}; a shorter prompt, fewer tools or a larger served window is the fix.
 */
public class PromptDoesNotFitContextException extends RuntimeException {

    public PromptDoesNotFitContextException(String message) {
        super(message);
    }
}
