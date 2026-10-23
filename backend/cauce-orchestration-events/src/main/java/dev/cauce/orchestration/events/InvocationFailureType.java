package dev.cauce.orchestration.events;

/**
 * The permanent-failure paths of the invocation lifecycle, mirroring the worker's and
 * reaper's terminal transitions (queue row FAILED or ABANDONED). Retry releases are not
 * represented: they are not permanent.
 */
public enum InvocationFailureType {

    /** A non-retryable LLM provider error (e.g. HTTP 400/401) — row FAILED. */
    LLM_ERROR,

    /**
     * The model rejected a parameter the agent configures (e.g. a sampling temperature it no
     * longer accepts): the agent must be reconfigured; retrying would never help — row FAILED.
     */
    AGENT_CONFIGURATION,

    /** A retryable LLM provider error after the attempt budget ran out — row ABANDONED. */
    LLM_RETRIES_EXHAUSTED,

    /** The agentic loop hit its hard iteration cap without a final reply — row FAILED. */
    MAX_TOOL_ITERATIONS,

    /**
     * The model ended its turn with no text and no tool calls (ADR 0005, C1): an empty reply
     * is a failure, never a message — row FAILED.
     */
    EMPTY_REPLY,

    /**
     * A non-LLM setup error: conversation/agent/message not found, invalid trigger message,
     * provider not configured, context too large, and the like — row FAILED.
     */
    SETUP_ERROR,

    /**
     * The client spent its monthly spend limit (ADR 0010, S6): the model is not called and the
     * person gets the fallback reply until the month ends or the limit is raised — row FAILED.
     */
    SPEND_LIMIT_REACHED,

    /**
     * The reaper abandoned an orphaned claim (worker died mid-processing) whose attempt
     * budget was exhausted — row ABANDONED.
     */
    REAPER_ABANDONED
}
