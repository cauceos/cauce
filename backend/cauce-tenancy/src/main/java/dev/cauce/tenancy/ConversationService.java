package dev.cauce.tenancy;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.conversation.ConversationEntity;
import dev.cauce.memory.conversation.ConversationMapper;
import dev.cauce.memory.conversation.ConversationRepository;
import java.util.List;
import java.util.Optional;
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
 *
 * <p>A conversation is keyed by {@code (agent, identity, channel config)} (ADR 0004, PC-1):
 * the identity is the person, the config the door they came in through. Resolution runs agent
 * &rarr; identity &rarr; conversation in the caller's transaction; the identity is owned by the
 * agent's tenant, never by the acting tenant of the request.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversationRepository;
    private final AgentRepository agentRepository;
    private final ConversationMapper conversationMapper;
    private final IdentityService identityService;

    public ConversationService(ConversationRepository conversationRepository,
                               AgentRepository agentRepository,
                               ConversationMapper conversationMapper,
                               IdentityService identityService) {
        this.conversationRepository = conversationRepository;
        this.agentRepository = agentRepository;
        this.conversationMapper = conversationMapper;
        this.identityService = identityService;
    }

    /**
     * Opens a conversation of {@code agentId} with the interlocutor {@code (kind, identityValue)}
     * on {@code channelType}, resolving (or creating) the identity first. The agent must be
     * visible under the current tenant context (RLS); otherwise it is reported as not found.
     * Unlike {@link #resolveOrStartConversation} it does not reuse an open thread, so a second
     * call for the same key violates the open-thread index.
     *
     * @param channelConfigId the channel instance the thread came in through; {@code null}
     *     exactly when {@code channelType} is the built-in {@code api} channel
     */
    @Transactional
    public Conversation startConversation(UUID agentId, String channelType, IdentityKind kind,
                                          String identityValue, UUID channelConfigId) {
        AgentEntity agent = requireVisibleAgentForSupportedChannel(agentId, channelType, channelConfigId);
        Identity identity = identityService.resolveOrCreate(agent.getTenantId(), channelType, kind, identityValue);
        Conversation conversation = Conversation.start(agent.getId(), identity, channelConfigId);
        return conversationMapper.toDomain(conversationRepository.save(conversationMapper.toEntity(conversation)));
    }

    /** Returns the conversation only if it is visible under the current context (RLS). */
    @Transactional
    public Optional<Conversation> getConversation(UUID conversationId) {
        return conversationRepository.findById(conversationId).map(conversationMapper::toDomain);
    }

    /**
     * Finds the active (OPEN) conversation of {@code identityId} with an agent through a channel
     * instance ({@code null} config for the {@code api} channel), if one exists.
     */
    @Transactional
    public Optional<Conversation> findActiveConversation(UUID agentId, UUID identityId,
                                                         UUID channelConfigId) {
        return conversationRepository
                .findByAgentIdAndIdentityIdAndChannelConfigIdAndStatus(
                        agentId, identityId, channelConfigId, ConversationStatus.OPEN)
                .map(conversationMapper::toDomain);
    }

    /**
     * Resolves the live thread of an inbound message: the agent (visible under RLS), then the
     * interlocutor's identity (owned by the agent's tenant), then the OPEN conversation for
     * {@code (agent, identity, channel config)}, starting one when none is open. This is the
     * channel-agnostic entry point shared by the REST messaging endpoint and the channel
     * adapters; every step joins the caller's transaction.
     *
     * <p>Both creations are race-free insert-first upserts: the identity on the V25
     * {@code identities_key} constraint (inside {@link IdentityService#resolveOrCreate}), the
     * conversation on the V26 open-thread index, which is {@code NULLS NOT DISTINCT} so that
     * the {@code api} channel's NULL config deduplicates as well. A concurrent first message
     * skips instead of throwing (which would poison the transaction) and re-reads the winner,
     * so duplicate webhook deliveries cannot split a thread.
     *
     * @param channelConfigId the channel instance the message came in through; {@code null}
     *     exactly when {@code channelType} is the built-in {@code api} channel
     * @throws dev.cauce.core.agent.AgentNotFoundException if the agent is not visible
     * @throws dev.cauce.core.conversation.InvalidChannelTypeException if the channel type is not supported
     * @throws IllegalArgumentException if {@code channelConfigId} breaks the api/adapter invariant
     */
    @Transactional
    public Conversation resolveOrStartConversation(UUID agentId, String channelType, IdentityKind kind,
                                                   String identityValue, UUID channelConfigId) {
        AgentEntity agent = requireVisibleAgentForSupportedChannel(agentId, channelType, channelConfigId);
        Identity identity = identityService.resolveOrCreate(agent.getTenantId(), channelType, kind, identityValue);
        Optional<Conversation> active = findActiveConversation(agent.getId(), identity.id(), channelConfigId);
        if (active.isPresent()) {
            return active.get();
        }
        Conversation candidate = Conversation.start(agent.getId(), identity, channelConfigId);
        int inserted = conversationRepository.insertOpenConversationIfAbsent(
                candidate.id(), candidate.agentId(), candidate.identityId(),
                channelConfigId, candidate.channelType(), candidate.externalIdentityRef());
        if (inserted == 0) {
            log.debug("Concurrent OPEN conversation for agent {} and identity {}; reusing the existing thread",
                    candidate.agentId(), candidate.identityId());
        }
        return findActiveConversation(candidate.agentId(), candidate.identityId(), channelConfigId)
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
     * tenant context (RLS), that the channel type is supported, and the channel-config invariant
     * of PC-1: an adapter-ingested thread names its config, an {@code api} thread has none. The
     * invariant lives here, not in a CHECK, because the database does not know the channel list.
     */
    private AgentEntity requireVisibleAgentForSupportedChannel(UUID agentId, String channelType,
                                                               UUID channelConfigId) {
        AgentEntity agent = agentRepository.findById(agentId).orElseThrow(() ->
                new AgentNotFoundException("No agent found for id " + agentId));
        SupportedChannels.requireSupported(channelType);
        boolean api = SupportedChannels.API.equals(channelType);
        if (api && channelConfigId != null) {
            throw new IllegalArgumentException("The api channel has no channel config");
        }
        if (!api && channelConfigId == null) {
            throw new IllegalArgumentException("Channel '" + channelType + "' requires its channel config");
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
