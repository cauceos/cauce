package dev.cauce.orchestration.audit;

import dev.cauce.core.audit.AuditContentHash;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditToolHash;
import dev.cauce.core.message.Message;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The Family-A auditable vocabulary: runtime conduct of the agentic loop. This class gives
 * the previously semantics-free {@code event_type} its first real meaning and builds each
 * event's payload — NON-SENSITIVE metadata plus a {@link AuditContentHash content hash} that
 * binds the record to the exact message content WITHOUT storing it. Never in a payload: raw
 * message text, an identity's value (erasable personal data), or raw provider error detail
 * (it can echo content). The interlocutor enters only as the opaque {@code identity_id}
 * (ADR 0004, PC-3), so erasing the identity row never touches the chain. Erasable content lives in the mutable business tables,
 * where deletion operates; the append-only chain keeps only its hash.
 *
 * <p><b>Emitted</b> — each inside the transaction of the business fact it audits:
 * {@link #MESSAGE_RECEIVED} (the ingest transaction), {@link #TOOL_CALLED} and
 * {@link #TOOL_RETURNED} (the append of each TOOL_CALL and TOOL_RESULT message, ADR 0005, C5 —
 * the tool's arguments and output enter only as {@link AuditToolHash} hashes), {@link #AGENT_RESPONDED} (the final
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
 * and {@link #REPLY_DELIVERED} (outbound delivery is best-effort fire-and-forget today; the
 * hook activates when transactional delivery lands). {@code conduct.human.escalation}, reserved
 * here first, is emitted by cauce-business since handoffs exist (ADR 0007, B7); its constant
 * lives with that emitter.
 */
public final class ConductAuditEvents {

    // Emitted types.
    public static final String MESSAGE_RECEIVED = "conduct.message.received";
    public static final String AGENT_RESPONDED = "conduct.agent.responded";
    public static final String INVOCATION_FAILED = "conduct.invocation.failed";
    public static final String TOOL_CALLED = "conduct.tool.called";
    public static final String TOOL_RETURNED = "conduct.tool.returned";

    // Reserved hook types — defined, never emitted until a transactional trigger exists.
    public static final String EXTERNAL_ACTION = "conduct.external.action";
    public static final String DATA_ACCESS = "conduct.data.access";
    public static final String REPLY_DELIVERED = "conduct.reply.delivered";

    private ConductAuditEvents() {
    }

    /** An inbound USER message was accepted and its invocation enqueued (the ingest tx). */
    public static AuditEvent messageReceived(UUID tenantId, UUID invocationId, UUID agentId,
                                             String channelType, UUID identityId, Message userMessage) {
        Objects.requireNonNull(channelType, "channelType must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(userMessage, "userMessage must not be null");
        return new AuditEvent(tenantId, MESSAGE_RECEIVED, Map.of(
                "invocation_id", invocationId.toString(),
                "conversation_id", userMessage.conversationId().toString(),
                "message_id", userMessage.id().toString(),
                "agent_id", agentId.toString(),
                "channel_type", channelType,
                "identity_id", identityId.toString(),
                "content_hash", AuditContentHash.of(tenantId, userMessage.content()),
                "content_length", userMessage.content().length()));
    }

    /**
     * The model asked for a tool and the TOOL_CALL message was persisted (its append tx). The
     * arguments enter only as their hash ({@link AuditToolHash}); {@code round} is the LLM round
     * that produced the call, counted per inbound message.
     */
    public static AuditEvent toolCalled(UUID tenantId, UUID invocationId, UUID agentId,
                                        Message toolCallMessage, ToolCall call, int round) {
        Objects.requireNonNull(toolCallMessage, "toolCallMessage must not be null");
        Objects.requireNonNull(call, "call must not be null");
        return new AuditEvent(tenantId, TOOL_CALLED, Map.of(
                "invocation_id", invocationId.toString(),
                "conversation_id", toolCallMessage.conversationId().toString(),
                "message_id", toolCallMessage.id().toString(),
                "agent_id", agentId.toString(),
                "tool_name", call.toolName(),
                "tool_call_id", call.toolCallId(),
                "round", round,
                "arguments_hash", AuditToolHash.of(tenantId, call.input())));
    }

    /**
     * A tool call finished and its TOOL_RESULT message was persisted (its append tx): how long it
     * ran, whether it succeeded, and the hash of its output — never the output itself, which can
     * carry whatever the tool read.
     */
    public static AuditEvent toolReturned(UUID tenantId, UUID invocationId, UUID agentId,
                                          Message toolResultMessage, ToolResult result, int round,
                                          long durationMillis) {
        Objects.requireNonNull(toolResultMessage, "toolResultMessage must not be null");
        Objects.requireNonNull(result, "result must not be null");
        return new AuditEvent(tenantId, TOOL_RETURNED, Map.of(
                "invocation_id", invocationId.toString(),
                "conversation_id", toolResultMessage.conversationId().toString(),
                "message_id", toolResultMessage.id().toString(),
                "agent_id", agentId.toString(),
                "tool_name", result.toolName(),
                "tool_call_id", result.toolCallId(),
                "round", round,
                "duration_ms", durationMillis,
                "outcome", result.isError() ? "error" : "success",
                "output_hash", AuditToolHash.of(tenantId, result.output())));
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
