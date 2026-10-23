package dev.cauce.llm.model;

/**
 * Token accounting for a single invocation. {@code inputTokens} is the whole prompt, cached or
 * not; {@code cacheReadTokens} and {@code cacheWriteTokens} say how much of it a provider that
 * caches prompt prefixes served from its cache or wrote to it (both zero for one that does not).
 * {@code cacheWriteLongTokens} is the part of {@code cacheWriteTokens} written for a long retention
 * (an hour on Anthropic, against five minutes), which costs more to write; zero for a provider that
 * offers one retention only.
 * {@code totalTokens} is normally {@code inputTokens + outputTokens}; use the factories.
 */
public record LlmUsage(int inputTokens, int outputTokens, int totalTokens, int cacheReadTokens,
                       int cacheWriteTokens, int cacheWriteLongTokens) {

    public LlmUsage {
        if (cacheReadTokens < 0 || cacheWriteTokens < 0 || cacheReadTokens + cacheWriteTokens > inputTokens) {
            throw new IllegalArgumentException("cached tokens must be non-negative and part of the input");
        }
        if (cacheWriteLongTokens < 0 || cacheWriteLongTokens > cacheWriteTokens) {
            throw new IllegalArgumentException("long-retention writes must be non-negative and part of the writes");
        }
    }

    /** Usage without prompt caching. */
    public static LlmUsage of(int inputTokens, int outputTokens) {
        return of(inputTokens, outputTokens, 0, 0);
    }

    /** Usage of which {@code cacheReadTokens} and {@code cacheWriteTokens} are part of {@code inputTokens}. */
    public static LlmUsage of(int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens) {
        return of(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, 0);
    }

    /** As {@link #of(int, int, int, int)}, with {@code cacheWriteLongTokens} of the writes for a long retention. */
    public static LlmUsage of(int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens,
                              int cacheWriteLongTokens) {
        return new LlmUsage(inputTokens, outputTokens, inputTokens + outputTokens, cacheReadTokens,
                cacheWriteTokens, cacheWriteLongTokens);
    }
}
