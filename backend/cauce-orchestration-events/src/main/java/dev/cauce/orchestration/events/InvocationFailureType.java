package dev.cauce.orchestration.events;

/**
 * The permanent-failure paths of the invocation lifecycle, mirroring the worker's and
 * reaper's terminal transitions (queue row FAILED or ABANDONED). Retry releases are not
 * represented: they are not permanent.
 */
public enum InvocationFailureType {

    /** A non-retryable LLM provider error (e.g. HTTP 400/401) — row FAILED. */
    LLM_ERROR,

    /** A retryable LLM provider error after the attempt budget ran out — row ABANDONED. */
    LLM_RETRIES_EXHAUSTED,

    /** The agentic loop hit its hard iteration cap without a final reply — row FAILED. */
    MAX_TOOL_ITERATIONS,

    /**
     * A non-LLM setup error: conversation/agent/message not found, invalid trigger message,
     * provider not configured, context too large, and the like — row FAILED.
     */
    SETUP_ERROR,

    /**
     * The reaper abandoned an orphaned claim (worker died mid-processing) whose attempt
     * budget was exhausted — row ABANDONED.
     */
    REAPER_ABANDONED
}
