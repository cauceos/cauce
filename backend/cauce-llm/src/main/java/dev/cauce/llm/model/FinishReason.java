package dev.cauce.llm.model;

/**
 * Why the model stopped generating, normalised across providers.
 */
public enum FinishReason {
    /** Natural completion (e.g. Anthropic {@code end_turn}/{@code stop_sequence}). */
    STOP,
    /** Output truncated by the token limit. */
    MAX_TOKENS,
    /** The model requested a tool call (not yet exercised). */
    TOOL_USE,
    /** Generation ended in an error condition. */
    ERROR
}
