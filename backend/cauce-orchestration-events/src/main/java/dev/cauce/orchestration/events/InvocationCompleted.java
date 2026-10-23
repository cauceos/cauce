package dev.cauce.orchestration.events;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The loop produced a final (tool-free) AGENT reply. Emitted right after that reply's
 * message has been persisted (its own committed transaction); the queue row transitions to
 * COMPLETED immediately afterwards in the worker. In the rare window where that transition
 * fails, the reaper re-runs the invocation and its events re-fire — the stream is
 * at-least-once by design.
 *
 * @param invocationId the invocation that completed
 * @param tenantId the tenant of the agent that replied
 * @param agentId the agent that replied
 * @param roundCount how many LLM rounds the loop ran (1 for a plain, no-tools reply)
 * @param finalMessageId the persisted final AGENT message
 * @param turnLatency from the person's message to the reply, both stamped by the database clock
 *     (ADR 0008); null when either stamp is missing
 * @param occurredAt when the reply was persisted
 */
public record InvocationCompleted(
        UUID invocationId,
        UUID tenantId,
        UUID agentId,
        int roundCount,
        UUID finalMessageId,
        Duration turnLatency,
        Instant occurredAt) implements OrchestrationEvent {

    public InvocationCompleted {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentId, "agentId");
        EventPreconditions.requireNonNegative(roundCount, "roundCount");
        Objects.requireNonNull(finalMessageId, "finalMessageId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
