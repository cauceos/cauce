package dev.cauce.orchestration.events;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An inbound USER message was ingested and its invocation enqueued for the async worker.
 *
 * <p>Emitted inside the (single) ingest transaction, right after the enqueue: if that
 * transaction rolls back, this event will have been published for work that never existed.
 * Harmless today (no consumers); a consumer that persists must listen with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} or an outbox.
 *
 * @param invocationId the enqueued {@code pending_invocations} row
 * @param tenantId the invocation's owning tenant (the conversation agent's tenant, as
 *     resolved by the enqueue — not necessarily the acting tenant)
 * @param agentId the agent that will produce the reply
 * @param conversationId the conversation the message belongs to
 * @param messageId the ingested USER message that triggered the invocation
 * @param occurredAt when the ingest happened
 * @param sincePreviousMessage the time since the same person's previous message in this
 *     conversation, both stamped by the database clock; null for the first (ADR 0010, S10)
 */
public record InvocationRequested(
        UUID invocationId,
        UUID tenantId,
        UUID agentId,
        UUID conversationId,
        UUID messageId,
        Instant occurredAt,
        Duration sincePreviousMessage) implements OrchestrationEvent {

    public InvocationRequested {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** The first message of a conversation: no previous one. */
    public InvocationRequested(UUID invocationId, UUID tenantId, UUID agentId, UUID conversationId, UUID messageId,
                               Instant occurredAt) {
        this(invocationId, tenantId, agentId, conversationId, messageId, occurredAt, null);
    }
}
