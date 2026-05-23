package dev.cauce.llm.model;

/**
 * Token accounting for a single invocation. {@code totalTokens} is normally
 * {@code inputTokens + outputTokens}; use {@link #of(int, int)} to compute it.
 */
public record LlmUsage(int inputTokens, int outputTokens, int totalTokens) {

    public static LlmUsage of(int inputTokens, int outputTokens) {
        return new LlmUsage(inputTokens, outputTokens, inputTokens + outputTokens);
    }
}
