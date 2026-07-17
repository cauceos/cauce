package dev.cauce.api.invocation;

import dev.cauce.orchestration.events.InvocationFailureType;

/**
 * Public failure cause of a permanently failed invocation. Cauce's own vocabulary, mapped
 * from the internal {@link InvocationFailureType} taxonomy so internal names (and the raw
 * provider error detail, which never leaves the backend) stay decoupled from the wire
 * contract — the same principle as the stable error codes in {@code GlobalExceptionHandler}.
 */
public enum FailureReason {

    /** The LLM provider rejected the request with a non-retryable error. */
    PROVIDER_ERROR,
    /** The LLM provider kept failing transiently until the retry budget ran out. */
    PROVIDER_UNAVAILABLE,
    /** The agentic loop hit its hard tool-iteration cap. */
    AGENT_LOOP_LIMIT,
    /** A non-provider setup error (missing conversation/agent, misconfiguration, ...). */
    INTERNAL_ERROR,
    /** Processing stalled and the claim timed out. */
    TIMEOUT;

    /** Maps the internal failure taxonomy to the public vocabulary. */
    public static FailureReason from(InvocationFailureType type) {
        return switch (type) {
            case LLM_ERROR -> PROVIDER_ERROR;
            case LLM_RETRIES_EXHAUSTED -> PROVIDER_UNAVAILABLE;
            case MAX_TOOL_ITERATIONS -> AGENT_LOOP_LIMIT;
            case SETUP_ERROR -> INTERNAL_ERROR;
            case REAPER_ABANDONED -> TIMEOUT;
        };
    }
}
