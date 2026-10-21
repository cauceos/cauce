package dev.cauce.orchestration.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform settings of the agentic tool loop ({@code cauce.orchestration.loop.*}).
 *
 * <p>{@code max-iterations} bounds the LLM rounds spent on one inbound message, across every
 * attempt of its invocation (ADR 0005, C6): rounds already persisted for the trigger message by
 * an earlier attempt count against it. The reaper's orphan timeout is sized for the default;
 * raising the cap far beyond it needs the timeout raised too.
 */
@ConfigurationProperties(prefix = "cauce.orchestration.loop")
public class AgenticLoopProperties {

    /** Platform default of {@link #getMaxIterations()}. */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    private int maxIterations = DEFAULT_MAX_ITERATIONS;

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("cauce.orchestration.loop.max-iterations must be at least 1");
        }
        this.maxIterations = maxIterations;
    }
}
