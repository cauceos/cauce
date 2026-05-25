package dev.cauce.orchestration;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.message.MessageNotFoundException;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.conversation.ConversationEntity;
import dev.cauce.memory.conversation.ConversationRepository;
import dev.cauce.memory.message.MessageRepository;
import dev.cauce.orchestration.persistence.PendingInvocationMapper;
import dev.cauce.orchestration.persistence.PendingInvocationRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service that enqueues and reads {@link PendingInvocation}s. Every method is
 * tenant-scoped: {@code RlsContextAspect} establishes the RLS context from
 * {@code TenantContext} before each transactional method runs, and Row-Level Security
 * filters every query by the visibility of the owning tenant.
 *
 * <p>The worker that drains the queue (claiming, retrying, processing) lands in a later
 * commit; this service only covers enqueue and read.
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
}
