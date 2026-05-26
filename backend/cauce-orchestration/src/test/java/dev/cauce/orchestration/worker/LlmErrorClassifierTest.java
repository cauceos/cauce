package dev.cauce.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmInvalidRequestException;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.exception.LlmRateLimitException;
import dev.cauce.llm.exception.LlmTimeoutException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class LlmErrorClassifierTest {

    private static final String PROVIDER = "anthropic";
    private static final String MODEL = "claude-sonnet-4-7";

    @Test
    void rateLimit_isRetryable() {
        assertThat(LlmErrorClassifier.isRetryable(
                new LlmRateLimitException(PROVIDER, MODEL, "429 throttled"))).isTrue();
    }

    @Test
    void timeout_isRetryable() {
        assertThat(LlmErrorClassifier.isRetryable(
                new LlmTimeoutException(PROVIDER, MODEL, "deadline exceeded"))).isTrue();
    }

    @Test
    void authentication_isNotRetryable() {
        assertThat(LlmErrorClassifier.isRetryable(
                new LlmAuthenticationException(PROVIDER, MODEL, "401 unauthorized"))).isFalse();
    }

    @Test
    void invalidRequest_isNotRetryable() {
        assertThat(LlmErrorClassifier.isRetryable(
                new LlmInvalidRequestException(PROVIDER, MODEL, "400 bad request"))).isFalse();
    }

    @Test
    void unspecializedProviderException_isRetryable_conservativeDefault() {
        // The unspecialized provider exception is treated as retryable: an unclassified
        // adapter failure is more likely a 5xx than a 4xx, and missing a transient retry
        // is worse than retrying a non-transient one.
        assertThat(LlmErrorClassifier.isRetryable(
                new LlmProviderException(PROVIDER, MODEL, "5xx upstream error"))).isTrue();
    }

    @Test
    void agentNotFound_isNotRetryable_setupError() {
        assertThat(LlmErrorClassifier.isRetryable(
                new AgentNotFoundException("missing"))).isFalse();
    }

    @Test
    void unrelatedRuntimeException_isNotRetryable() {
        assertThat(LlmErrorClassifier.isRetryable(
                new IllegalStateException("bug"))).isFalse();
    }

    @Test
    void unrelatedCheckedException_isNotRetryable() {
        assertThat(LlmErrorClassifier.isRetryable(new IOException("disk"))).isFalse();
    }
}
