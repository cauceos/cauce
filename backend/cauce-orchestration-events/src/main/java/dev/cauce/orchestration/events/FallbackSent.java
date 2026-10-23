package dev.cauce.orchestration.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The person got the agent's fallback reply instead of an answer (ADR 0005, C3): the invocation
 * failed for good and the FALLBACK message has been persisted. Emitted once per terminal failure
 * whose fallback could be recorded, so fallbacks over requested invocations, per agent, is the share
 * of messages the agent could not answer (ADR 0011, M1).
 *
 * @param invocationId the invocation that failed
 * @param tenantId the tenant of the agent
 * @param agentId the agent that could not answer
 * @param failureType why the invocation failed
 * @param occurredAt when the fallback was persisted
 */
public record FallbackSent(
        UUID invocationId,
        UUID tenantId,
        UUID agentId,
        InvocationFailureType failureType,
        Instant occurredAt) implements OrchestrationEvent {

    public FallbackSent {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(failureType, "failureType");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
