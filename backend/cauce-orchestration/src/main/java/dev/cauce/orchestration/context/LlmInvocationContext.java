package dev.cauce.orchestration.context;

import dev.cauce.llm.model.LlmMessage;
import java.util.List;

/**
 * The conversation context assembled for one LLM invocation: the messages that fit the
 * model's effective context window, in chronological order, plus the metadata used to
 * produce them. Immutable.
 *
 * <p>Distinct from {@link dev.cauce.llm.model.LlmInvocation} (the SPI request type): this
 * is the orchestration-layer precursor that the orchestrator (later commit) turns into an
 * {@code LlmInvocation} by adding the credential, max tokens, and other call parameters.
 *
 * @param messages           the selected messages, oldest first
 * @param systemPrompt       the agent's system prompt, passed through verbatim (NOT counted
 *                           against the window in v1.0)
 * @param estimatedTokens    estimated tokens of {@code messages} (excludes the system prompt)
 * @param contextWindowLimit the model's total context window, for info/debugging
 * @param reservedForResponse tokens held back for the response plus buffer: 8K + 2K, or half
 *                           of a window too small for that
 */
public record LlmInvocationContext(
        List<LlmMessage> messages,
        String systemPrompt,
        int estimatedTokens,
        int contextWindowLimit,
        int reservedForResponse) {

    public LlmInvocationContext {
        messages = List.copyOf(messages);
    }

    /**
     * The response tokens to ask the model for, given the agent's {@code requested}: in a window
     * too small for the standard reservation, no more than was held back for the response, so
     * that history and response together fit the window the provider serves. Otherwise the
     * agent's own number, unchanged.
     */
    public int responseTokens(int requested) {
        return reservedForResponse < ContextBuilder.RESERVED_FOR_RESPONSE
                ? Math.min(requested, reservedForResponse)
                : requested;
    }
}
