package dev.cauce.tenancy;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.conversation.InvalidChannelTypeException;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.conversation.ConversationEntity;
import dev.cauce.memory.conversation.ConversationMapper;
import dev.cauce.memory.conversation.ConversationRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for managing conversations. Every method is tenant-scoped:
 * {@code RlsContextAspect} establishes the RLS context from {@code TenantContext}
 * before each transactional method runs, and Row-Level Security filters every query.
 *
 * <p>Accessibility is enforced by RLS, not by an owner check: the agent (and its
 * conversations) is visible to its CLIENT tenant <em>and</em> to the partner and
 * operator above it. This supports the B2B2B model where a partner operates on behalf
 * of its clients — e.g. routing an inbound webhook to a client's agent under the
 * partner's context. A lookup that RLS filters out is reported as not-found, so the
 * API does not reveal entities outside the caller's scope.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    // "api" is the reserved built-in channel for the REST messaging endpoint: a first-class
    // origin handled in-process, not a pluggable adapter. The rest are placeholders.
    // TODO: replace hardcoded validation with the cauce-channels channel registry when the SPI is implemented.
    private static final Set<String> SUPPORTED_CHANNELS = Set.of("api", "whatsapp", "voice", "email", "web_chat");

    private final ConversationRepository conversationRepository;
    private final AgentRepository agentRepository;
    private final ConversationMapper conversationMapper;

    public ConversationService(ConversationRepository conversationRepository,
                               AgentRepository agentRepository,
                               ConversationMapper conversationMapper) {
        this.conversationRepository = conversationRepository;
        this.agentRepository = agentRepository;
        this.conversationMapper = conversationMapper;
    }

    /**
     * Opens a conversation for {@code agentId}. The agent must be visible under the
     * current tenant context (RLS); otherwise it is reported as not found.
     */
    @Transactional
    public Conversation startConversation(UUID agentId, String channelType, String externalIdentityRef) {
        AgentEntity agent = requireVisibleAgentForSupportedChannel(agentId, channelType);
        Conversation conversation = Conversation.start(agent.getId(), channelType, externalIdentityRef);
        return conversationMapper.toDomain(conversationRepository.save(conversationMapper.toEntity(conversation)));
    }

    /** Returns the conversation only if it is visible under the current context (RLS). */
    @Transactional
    public Optional<Conversation> getConversation(UUID conversationId) {
        return conversationRepository.findById(conversationId).map(conversationMapper::toDomain);
    }

    /**
     * Finds the active (OPEN) conversation of an external user with an agent on a given
     * channel, if one exists. Used to decide whether an inbound message continues an
     * existing thread or should start a new one.
     */
    @Transactional
    public Optional<Conversation> findActiveConversation(UUID agentId, String channelType,
                                                         String externalIdentityRef) {
        return conversationRepository
                .findByAgentIdAndChannelTypeAndExternalIdentityRefAndStatus(
                        agentId, channelType, externalIdentityRef, ConversationStatus.OPEN)
                .map(conversationMapper::toDomain);
    }

    /**
     * Resolves the active conversation for the {@code (agent, channel, external identity)} tuple,
     * starting a new one when none is open. This is the channel-agnostic entry point an inbound
     * message uses to continue an existing thread or begin a new one; the REST messaging endpoint
     * and future cauce-channels adapters share it. The find and the conditional create run in one
     * transaction under the same tenant RLS context; a non-visible agent surfaces as not-found via
     * {@link #startConversation}.
     *
     * <p>There is no database uniqueness on an open thread, so two concurrent first messages for
     * the same tuple could each open a conversation. Accepted for now (no schema change).
     *
     * @throws dev.cauce.core.agent.AgentNotFoundException if the agent is not visible
     * @throws InvalidChannelTypeException if the channel type is not supported
     */
    @Transactional
    public Conversation resolveOrStartConversation(UUID agentId, String channelType,
                                                   String externalIdentityRef) {
        Optional<Conversation> active = findActiveConversation(agentId, channelType, externalIdentityRef);
        if (active.isPresent()) {
            return active.get();
        }
        AgentEntity agent = requireVisibleAgentForSupportedChannel(agentId, channelType);
        Conversation candidate = Conversation.start(agent.getId(), channelType, externalIdentityRef);
        // Race-free create: at most one OPEN row per tuple is guaranteed by the V13 partial
        // unique index; ON CONFLICT DO NOTHING means a concurrent first message does not throw
        // (which would poison this transaction) — it simply skips, and we re-read the winner.
        int inserted = conversationRepository.insertOpenConversationIfAbsent(
                candidate.id(), candidate.agentId(), candidate.channelType(),
                candidate.externalIdentityRef());
        if (inserted == 0) {
            log.debug("Concurrent OPEN conversation for agent {} on channel {}; reusing the existing thread",
                    candidate.agentId(), candidate.channelType());
        }
        return conversationRepository
                .findByAgentIdAndChannelTypeAndExternalIdentityRefAndStatus(
                        candidate.agentId(), candidate.channelType(),
                        candidate.externalIdentityRef(), ConversationStatus.OPEN)
                .map(conversationMapper::toDomain)
                .orElseThrow(() -> new IllegalStateException(
                        "Expected an OPEN conversation after insert-or-skip for agent " + candidate.agentId()));
    }

    /** Lists the conversations of an agent; RLS filters out agents the context cannot see. */
    @Transactional
    public List<Conversation> listConversationsForAgent(UUID agentId) {
        return conversationRepository.findByAgentId(agentId).stream()
                .map(conversationMapper::toDomain)
                .toList();
    }

    /**
     * Closes the conversation (OPEN or ESCALATED &rarr; CLOSED).
     *
     * @throws ConversationNotFoundException if not visible under the current context
     * @throws dev.cauce.core.conversation.InvalidConversationTransitionException
     *         if the conversation cannot be closed from its current status
     */
    @Transactional
    public Conversation closeConversation(UUID conversationId) {
        return applyTransition(conversationId, Conversation::close);
    }

    /**
     * Escalates the conversation to a human (OPEN &rarr; ESCALATED).
     *
     * @throws ConversationNotFoundException if not visible under the current context
     * @throws dev.cauce.core.conversation.InvalidConversationTransitionException
     *         if the conversation cannot be escalated from its current status
     */
    @Transactional
    public Conversation escalateConversation(UUID conversationId) {
        return applyTransition(conversationId, Conversation::escalate);
    }

    /**
     * Archives the conversation (any status except ARCHIVED &rarr; ARCHIVED).
     *
     * @throws ConversationNotFoundException if not visible under the current context
     * @throws dev.cauce.core.conversation.InvalidConversationTransitionException
     *         if the conversation is already archived
     */
    @Transactional
    public Conversation archiveConversation(UUID conversationId) {
        return applyTransition(conversationId, Conversation::archive);
    }

    /**
     * Loads the agent for a new conversation, enforcing that it is visible under the current
     * tenant context (RLS) and that the channel type is supported. Shared by
     * {@link #startConversation} and {@link #resolveOrStartConversation}.
     */
    private AgentEntity requireVisibleAgentForSupportedChannel(UUID agentId, String channelType) {
        AgentEntity agent = agentRepository.findById(agentId).orElseThrow(() ->
                new AgentNotFoundException("No agent found for id " + agentId));
        if (!SUPPORTED_CHANNELS.contains(channelType)) {
            throw new InvalidChannelTypeException("Unsupported channel type: " + channelType);
        }
        return agent;
    }

    /**
     * Loads a conversation (RLS-filtered), applies a domain transition, and persists the
     * result, all in the current transaction. An invalid transition throws and rolls the
     * transaction back, leaving the stored row untouched.
     */
    private Conversation applyTransition(UUID conversationId, UnaryOperator<Conversation> transition) {
        ConversationEntity entity = conversationRepository.findById(conversationId).orElseThrow(() ->
                new ConversationNotFoundException("No conversation found for id " + conversationId));
        Conversation updated = transition.apply(conversationMapper.toDomain(entity));
        return conversationMapper.toDomain(conversationRepository.save(conversationMapper.toEntity(updated)));
    }
}
