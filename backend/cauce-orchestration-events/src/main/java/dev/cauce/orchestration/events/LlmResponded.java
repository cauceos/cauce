package dev.cauce.orchestration.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One LLM call returned successfully. Carries the provider's token accounting — the hook
 * that per-tenant usage accounting and the audit log will consume; usage is logged today
 * but persisted nowhere else.
 *
 * <p>{@code finishReason} is the neutral finish reason's name (e.g. {@code "STOP"},
 * {@code "TOOL_USE"}) carried as a string so this module stays dependency-free.
 * Token field names mirror {@code LlmUsage} ({@code inputTokens}/{@code outputTokens}).
 *
 * @param invocationId the invocation being processed
 * @param provider the stable provider id (e.g. {@code "anthropic"})
 * @param modelName the model that responded
 * @param roundIndex zero-based loop round
 * @param finishReason why the model stopped, as the neutral {@code FinishReason} name
 * @param inputTokens prompt-side tokens billed for this call
 * @param outputTokens completion-side tokens billed for this call
 * @param totalTokens total tokens billed for this call
 * @param occurredAt when the response arrived
 */
public record LlmResponded(
        UUID invocationId,
        String provider,
        String modelName,
        int roundIndex,
        String finishReason,
        int inputTokens,
        int outputTokens,
        int totalTokens,
        Instant occurredAt) implements OrchestrationEvent {

    public LlmResponded {
        Objects.requireNonNull(invocationId, "invocationId");
        EventPreconditions.requireText(provider, "provider");
        EventPreconditions.requireText(modelName, "modelName");
        EventPreconditions.requireNonNegative(roundIndex, "roundIndex");
        EventPreconditions.requireText(finishReason, "finishReason");
        EventPreconditions.requireNonNegative(inputTokens, "inputTokens");
        EventPreconditions.requireNonNegative(outputTokens, "outputTokens");
        EventPreconditions.requireNonNegative(totalTokens, "totalTokens");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
