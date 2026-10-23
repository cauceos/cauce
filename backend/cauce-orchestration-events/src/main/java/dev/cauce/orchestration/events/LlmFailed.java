package dev.cauce.orchestration.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One LLM call failed (round {@code roundIndex} of the agentic loop): the provider answered with an
 * error, or did not answer. Failed calls over calls, per provider, is how the operator learns a
 * provider is down (ADR 0011, A1); counting them directly keeps that true when the provider answers
 * nothing at all.
 *
 * @param invocationId the invocation being processed
 * @param provider the stable provider id (e.g. {@code "anthropic"})
 * @param modelName the model that was invoked
 * @param roundIndex zero-based loop round
 * @param errorType the failure's exception type, never its message (which can echo the prompt)
 * @param occurredAt when the call failed
 */
public record LlmFailed(
        UUID invocationId,
        String provider,
        String modelName,
        int roundIndex,
        String errorType,
        Instant occurredAt) implements OrchestrationEvent {

    public LlmFailed {
        Objects.requireNonNull(invocationId, "invocationId");
        EventPreconditions.requireText(provider, "provider");
        EventPreconditions.requireText(modelName, "modelName");
        EventPreconditions.requireNonNegative(roundIndex, "roundIndex");
        EventPreconditions.requireText(errorType, "errorType");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
