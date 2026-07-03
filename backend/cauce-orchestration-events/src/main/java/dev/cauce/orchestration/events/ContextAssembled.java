package dev.cauce.orchestration.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The conversation context for one loop round was assembled within the model's window.
 * Emitted once per round — the loop rebuilds the context before every LLM call, so an
 * invocation with tool rounds emits several, showing the context grow round to round.
 *
 * @param invocationId the invocation being processed
 * @param roundIndex zero-based loop round this context was built for
 * @param modelName the target model whose context window was honoured
 * @param messageCount how many conversation messages fit the effective window
 * @param estimatedTokens estimated tokens of the selected messages (system prompt excluded)
 * @param contextWindowLimit the model's total context window
 * @param occurredAt when the context was assembled
 */
public record ContextAssembled(
        UUID invocationId,
        int roundIndex,
        String modelName,
        int messageCount,
        int estimatedTokens,
        int contextWindowLimit,
        Instant occurredAt) implements OrchestrationEvent {

    public ContextAssembled {
        Objects.requireNonNull(invocationId, "invocationId");
        EventPreconditions.requireNonNegative(roundIndex, "roundIndex");
        EventPreconditions.requireText(modelName, "modelName");
        EventPreconditions.requireNonNegative(messageCount, "messageCount");
        EventPreconditions.requireNonNegative(estimatedTokens, "estimatedTokens");
        EventPreconditions.requireNonNegative(contextWindowLimit, "contextWindowLimit");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
