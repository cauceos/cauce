package dev.cauce.orchestration.audit;

import dev.cauce.core.audit.AuditContentHash;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.message.Message;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The Family-A auditable vocabulary: runtime conduct of the agentic loop. This class gives
 * the previously semantics-free {@code event_type} its first real meaning and builds each
 * event's payload — NON-SENSITIVE metadata plus a {@link AuditContentHash content hash} that
 * binds the record to the exact message content WITHOUT storing it. Never in a payload: raw
 * message text, {@code external_identity_ref} (erasable personal data), or raw provider
 * error detail (it can echo content). Erasable content lives in the mutable business tables,
 * where deletion operates; the append-only chain keeps only its hash.
 *
 * <p><b>Emitted</b> — each inside the transaction of the business fact it audits:
 * {@link #MESSAGE_RECEIVED} (the ingest transaction), {@link #AGENT_RESPONDED} (the final
 * AGENT append — carrying {@code finish_reason} and {@code rounds}, it IS the terminal
 * success record; there is no separate COMPLETED audit event, because the queue's
 * PROCESSING→COMPLETED flip is bookkeeping, not conduct), and {@link #INVOCATION_FAILED}
 * (the terminal FAILED/ABANDONED transition, from worker and reaper alike).
 *
 * <p><b>Reserved hooks</b> — type defined now, emission impossible today because no
 * transactional trigger exists; wiring one before its trigger would fabricate audit facts:
 * {@link #EXTERNAL_ACTION} (no built-in tool has side effects — the only tool is the
 * read-only clock), {@link #DATA_ACCESS} (no tool reaches external data; context assembly
 * is mechanics and belongs to the observational event stream, not the chain),
 * {@link #HUMAN_ESCALATION} ({@code escalateConversation} has no production caller), and
 * {@link #REPLY_DELIVERED} (outbound delivery is best-effort fire-and-forget today; the
 * hook activates when transactional delivery lands).
 */
public final class ConductAuditEvents {

    // Emitted types.
    public static final String MESSAGE_RECEIVED = "conduct.message.received";
    public static final String AGENT_RESPONDED = "conduct.agent.responded";
    public static final String INVOCATION_FAILED = "conduct.invocation.failed";

    // Reserved hook types — defined, never emitted until a transactional trigger exists.
    public static final String EXTERNAL_ACTION = "conduct.external.action";
    public static final String DATA_ACCESS = "conduct.data.access";
    public static final String HUMAN_ESCALATION = "conduct.human.escalation";
    public static final String REPLY_DELIVERED = "conduct.reply.delivered";

    private ConductAuditEvents() {
    }

    /** An inbound USER message was accepted and its invocation enqueued (the ingest tx). */
    public static AuditEvent messageReceived(UUID tenantId, UUID invocationId, UUID agentId,
                                             String channelType, Message userMessage) {
        Objects.requireNonNull(channelType, "channelType must not be null");
        Objects.requireNonNull(userMessage, "userMessage must not be null");
        return new AuditEvent(tenantId, MESSAGE_RECEIVED, Map.of(
                "invocation_id", invocationId.toString(),
                "conversation_id", userMessage.conversationId().toString(),
                "message_id", userMessage.id().toString(),
                "agent_id", agentId.toString(),
                "channel_type", channelType,
                "content_hash", AuditContentHash.of(tenantId, userMessage.content()),
                "content_length", userMessage.content().length()));
    }

    /**
     * The agent's final reply was persisted (the append tx) — the terminal success record,
     * carrying how the invocation ended ({@code finish_reason}) and how many LLM rounds it
     * took ({@code rounds}).
     */
    public static AuditEvent agentResponded(UUID tenantId, UUID invocationId, UUID agentId,
                                            Message agentMessage, String finishReason,
                                            int rounds) {
        Objects.requireNonNull(agentMessage, "agentMessage must not be null");
        Objects.requireNonNull(finishReason, "finishReason must not be null");
        return new AuditEvent(tenantId, AGENT_RESPONDED, Map.of(
                "invocation_id", invocationId.toString(),
                "conversation_id", agentMessage.conversationId().toString(),
                "message_id", agentMessage.id().toString(),
                "agent_id", agentId.toString(),
                "finish_reason", finishReason,
                "rounds", rounds,
                "content_hash", AuditContentHash.of(tenantId, agentMessage.content()),
                "content_length", agentMessage.content().length()));
    }

    /**
     * The invocation reached a terminal failure (FAILED or ABANDONED transition tx). Only
     * the failure taxonomy is audited — the raw error detail stays on the mutable
     * {@code pending_invocations.last_error} row, since provider messages can echo content.
     */
    public static AuditEvent invocationFailed(UUID tenantId, UUID invocationId,
                                              UUID conversationId, String failureType) {
        Objects.requireNonNull(failureType, "failureType must not be null");
        return new AuditEvent(tenantId, INVOCATION_FAILED, Map.of(
                "invocation_id", invocationId.toString(),
                "conversation_id", conversationId.toString(),
                "failure_type", failureType));
    }
}
