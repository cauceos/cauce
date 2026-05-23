package dev.cauce.llm.spi;

import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmResponse;

/**
 * The pluggable contract every LLM provider adapter implements. The core depends only on
 * this interface; concrete adapters (Anthropic, OpenAI, …) live in separate
 * {@code cauce-llm-<provider>} modules and are discovered as Spring beans by
 * {@link LlmProviderRegistry}.
 *
 * <p><b>Threading:</b> {@link #invoke} is synchronous and blocking. Implementations must
 * be stateless and thread-safe; a single bean serves concurrent calls. Streaming is not
 * part of v1.0 and will be added as a separate method.
 */
public interface LlmProvider {

    /** Stable provider id, e.g. {@code "anthropic"}. Unique across registered providers. */
    String id();

    /**
     * Generates a completion for the invocation.
     *
     * @throws dev.cauce.llm.exception.LlmProviderException (or a subtype) on provider error
     */
    LlmResponse invoke(LlmInvocation invocation);

    /** Whether this provider can serve the given model, e.g. Anthropic for {@code claude-*}. */
    boolean supports(String modelName);
}
