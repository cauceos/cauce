package dev.cauce.orchestration.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One LLM call is about to be made (round {@code roundIndex} of the agentic loop).
 *
 * @param invocationId the invocation being processed
 * @param provider the stable provider id (e.g. {@code "anthropic"})
 * @param modelName the model being invoked
 * @param roundIndex zero-based loop round
 * @param occurredAt when the call was started
 */
public record LlmInvoked(
        UUID invocationId,
        String provider,
        String modelName,
        int roundIndex,
        Instant occurredAt) implements OrchestrationEvent {

    public LlmInvoked {
        Objects.requireNonNull(invocationId, "invocationId");
        EventPreconditions.requireText(provider, "provider");
        EventPreconditions.requireText(modelName, "modelName");
        EventPreconditions.requireNonNegative(roundIndex, "roundIndex");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
