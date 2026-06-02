package dev.cauce.orchestration;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.message.MessageNotFoundException;
import dev.cauce.core.tenant.NoTenantContext;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.conversation.ConversationEntity;
import dev.cauce.memory.conversation.ConversationRepository;
import dev.cauce.memory.message.MessageRepository;
import dev.cauce.orchestration.persistence.PendingInvocationMapper;
import dev.cauce.orchestration.persistence.PendingInvocationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service that enqueues, reads, and transitions {@link PendingInvocation}s.
 * Tenant-scoped operations (enqueue, read) rely on {@code RlsContextAspect} to set the RLS
 * context from {@code TenantContext} before each transactional method runs, and on
 * Row-Level Security to filter every query by the visibility of the owning tenant.
 *
 * <p>Worker-facing operations ({@link #claimNextBatch}, {@link #findOrphanedSince}) drain
 * the queue across all tenants and are therefore annotated {@link NoTenantContext}: there
 * is no single owning tenant for the polling itself. Per-row processing must establish
 * the row's owning tenant context before invoking tenant-scoped services downstream.
 *
 * <p>The per-row terminal transitions ({@link #markCompleted}, {@link #markFailed},
 * {@link #markAbandoned}, {@link #releaseForRetry}) are ordinary tenant-scoped operations:
 * the caller (the worker) sets {@code TenantContext} to the invocation's owning tenant
 * before invoking them.
 */
@Service
public class PendingInvocationService {

    private final PendingInvocationRepository pendingInvocationRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AgentRepository agentRepository;
    private final PendingInvocationMapper pendingInvocationMapper;

    public PendingInvocationService(PendingInvocationRepository pendingInvocationRepository,
                                    ConversationRepository conversationRepository,
                                    MessageRepository messageRepository,
                                    AgentRepository agentRepository,
                                    PendingInvocationMapper pendingInvocationMapper) {
        this.pendingInvocationRepository = pendingInvocationRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.agentRepository = agentRepository;
        this.pendingInvocationMapper = pendingInvocationMapper;
    }

    /**
     * Enqueues an LLM invocation for {@code conversationId}, triggered by the message
     * {@code triggerMessageId}. The conversation and the trigger message must be visible
     * under the current tenant context (RLS); otherwise they are reported as not found.
     *
     * <p>The invocation's owning tenant is resolved from the conversation's agent, not from
     * the acting tenant context: a partner may enqueue work on behalf of one of its
     * clients, and the row must belong to that client for hierarchical visibility to hold.
     *
     * @throws ConversationNotFoundException if the conversation does not exist or is not visible
     * @throws MessageNotFoundException if the trigger message does not belong to the conversation
     */
    @Transactional
    public PendingInvocation enqueueInvocation(UUID conversationId, UUID triggerMessageId) {
        ConversationEntity conversation = conversationRepository.findById(conversationId).orElseThrow(() ->
                new ConversationNotFoundException("No conversation found for id " + conversationId));

        messageRepository.findByIdAndConversationId(triggerMessageId, conversationId).orElseThrow(() ->
                new MessageNotFoundException("No message " + triggerMessageId
                        + " found in conversation " + conversationId));

        AgentEntity agent = agentRepository.findById(conversation.getAgentId()).orElseThrow(() ->
                new AgentNotFoundException("No agent found for id " + conversation.getAgentId()));

        PendingInvocation invocation =
                PendingInvocation.create(agent.getTenantId(), conversationId, triggerMessageId);
        return pendingInvocationMapper.toDomain(
                pendingInvocationRepository.save(pendingInvocationMapper.toEntity(invocation)));
    }

    /** Returns the invocation if it is visible under the current tenant context. */
    @Transactional
    public Optional<PendingInvocation> getPendingInvocation(UUID id) {
        return pendingInvocationRepository.findById(id).map(pendingInvocationMapper::toDomain);
    }

    /** Lists a conversation's queued invocations; RLS filters by visibility. */
    @Transactional
    public List<PendingInvocation> listPendingInvocationsForConversation(UUID conversationId) {
        return pendingInvocationRepository.findByConversationId(conversationId).stream()
                .map(pendingInvocationMapper::toDomain)
                .toList();
    }

    // === WORKER-FACING OPERATIONS (cross-tenant) ===

    /**
     * Claims up to {@code batchSize} of the oldest PENDING rows whose backoff has cleared and
     * marks each PROCESSING under {@code workerId}, returning the claimed rows.
     *
     * <p>{@link NoTenantContext}: the worker has no single owning tenant. The claim — the
     * PENDING -> PROCESSING transition under {@code FOR UPDATE SKIP LOCKED} — is performed
     * atomically by the {@code claim_pending_invocations} SECURITY DEFINER function (V12),
     * which is the narrow cross-tenant escape hatch (see ADR 0001). The rows returned are
     * already PROCESSING; this method only maps them to the domain. The aspect is exempted
     * so it does not fail-close on the missing context.
     */
    @Transactional
    @NoTenantContext
    public List<PendingInvocation> claimNextBatch(String workerId, int batchSize) {
        return pendingInvocationRepository.claimNextBatch(workerId, batchSize).stream()
                .map(pendingInvocationMapper::toDomain)
                .toList();
    }

    /**
     * Returns PROCESSING invocations whose claim is older than {@code threshold}. Used by
     * the reaper to recover work whose worker died (or hung) before transitioning the row.
     *
     * <p>{@link NoTenantContext}: same rationale as {@link #claimNextBatch}.
     */
    @Transactional(readOnly = true)
    @NoTenantContext
    public List<PendingInvocation> findOrphanedSince(Instant threshold) {
        return pendingInvocationRepository.findOrphanedSince(threshold).stream()
                .map(pendingInvocationMapper::toDomain)
                .toList();
    }

    // === PER-ROW TERMINAL TRANSITIONS (tenant-scoped) ===

    /**
     * Marks {@code invocationId} COMPLETED. The caller must have set {@code TenantContext}
     * to the invocation's owning tenant; RLS enforces that the caller may see the row.
     *
     * @throws PendingInvocationNotFoundException if the row is not visible under the current
     *     tenant context
     */
    @Transactional
    public void markCompleted(UUID invocationId) {
        PendingInvocation completed = loadVisible(invocationId).complete();
        pendingInvocationRepository.save(pendingInvocationMapper.toEntity(completed));
    }

    /**
     * Marks {@code invocationId} FAILED. The caller must have set {@code TenantContext} to
     * the invocation's owning tenant.
     */
    @Transactional
    public void markFailed(UUID invocationId, String errorMessage) {
        PendingInvocation failed = loadVisible(invocationId).fail(errorMessage);
        pendingInvocationRepository.save(pendingInvocationMapper.toEntity(failed));
    }

    /**
     * Marks {@code invocationId} ABANDONED. The caller must have set {@code TenantContext}
     * to the invocation's owning tenant.
     */
    @Transactional
    public void markAbandoned(UUID invocationId, String errorMessage) {
        PendingInvocation abandoned = loadVisible(invocationId).abandon(errorMessage);
        pendingInvocationRepository.save(pendingInvocationMapper.toEntity(abandoned));
    }

    /**
     * Releases {@code invocationId} back to PENDING with exponential backoff (see
     * {@link PendingInvocation#releaseForRetry}). The caller must have set
     * {@code TenantContext} to the invocation's owning tenant.
     */
    @Transactional
    public void releaseForRetry(UUID invocationId, String errorMessage, long baseIntervalSeconds) {
        PendingInvocation released =
                loadVisible(invocationId).releaseForRetry(errorMessage, baseIntervalSeconds);
        pendingInvocationRepository.save(pendingInvocationMapper.toEntity(released));
    }

    private PendingInvocation loadVisible(UUID invocationId) {
        return pendingInvocationRepository.findById(invocationId)
                .map(pendingInvocationMapper::toDomain)
                .orElseThrow(() -> new PendingInvocationNotFoundException(
                        "No pending invocation found for id " + invocationId));
    }
}
