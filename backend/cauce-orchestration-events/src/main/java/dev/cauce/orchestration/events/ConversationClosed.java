package dev.cauce.orchestration.events;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The thread the person wrote to was closed before their message, and the message opened a new one
 * (ADR 0013, T4). Published in the ingest of that message, with its invocation, so closed threads
 * over requested invocations, per agent, is how often a person comes back after the window.
 *
 * @param invocationId the invocation of the message that opened the new thread
 * @param tenantId the tenant of the agent
 * @param agentId the agent whose thread closed
 * @param reason why it closed: {@code inactivity}, the only reason so far
 * @param idle how long the thread had gone without a conversational message
 * @param occurredAt when it closed
 */
public record ConversationClosed(
        UUID invocationId,
        UUID tenantId,
        UUID agentId,
        String reason,
        Duration idle,
        Instant occurredAt) implements OrchestrationEvent {

    /** The one reason a thread closes on its own. */
    public static final String INACTIVITY = "inactivity";

    public ConversationClosed {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(idle, "idle");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
