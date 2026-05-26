package dev.cauce.orchestration.worker;

import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmInvalidRequestException;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.exception.LlmRateLimitException;
import dev.cauce.llm.exception.LlmTimeoutException;

/**
 * Decides whether an exception thrown during LLM invocation justifies another attempt or
 * should fail the {@code PendingInvocation} outright.
 *
 * <p>Policy:
 * <ul>
 *   <li>{@link LlmRateLimitException}, {@link LlmTimeoutException} → retryable</li>
 *   <li>{@link LlmAuthenticationException}, {@link LlmInvalidRequestException}
 *       → not retryable (the request will never succeed as-is)</li>
 *   <li>An unspecialized {@link LlmProviderException} → retryable (conservative: an
 *       unclassified provider error is more likely a 5xx than a 4xx, and the worst case
 *       of retrying a non-transient error is a log line — the worst case of failing a
 *       transient error is a lost invocation)</li>
 *   <li>Anything else (setup errors such as {@code ConversationNotFoundException},
 *       {@code AgentNotFoundException}, runtime bugs) → not retryable</li>
 * </ul>
 */
public final class LlmErrorClassifier {

    private LlmErrorClassifier() {
    }

    /** Returns whether {@code error} should release the invocation for another attempt. */
    public static boolean isRetryable(Throwable error) {
        if (error instanceof LlmRateLimitException || error instanceof LlmTimeoutException) {
            return true;
        }
        if (error instanceof LlmAuthenticationException || error instanceof LlmInvalidRequestException) {
            return false;
        }
        return error instanceof LlmProviderException;
    }
}
