package dev.cauce.tenancy;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.conversation.InvalidChannelTypeException;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.conversation.ConversationMapper;
import dev.cauce.memory.conversation.ConversationRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

    // TODO: replace hardcoded validation with the cauce-channels channel registry when the SPI is implemented.
    private static final Set<String> SUPPORTED_CHANNELS = Set.of("whatsapp", "voice", "email", "web_chat");

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
        AgentEntity agent = agentRepository.findById(agentId).orElseThrow(() ->
                new AgentNotFoundException("No agent found for id " + agentId));
        if (!SUPPORTED_CHANNELS.contains(channelType)) {
            throw new InvalidChannelTypeException("Unsupported channel type: " + channelType);
        }

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

    /** Lists the conversations of an agent; RLS filters out agents the context cannot see. */
    @Transactional
    public List<Conversation> listConversationsForAgent(UUID agentId) {
        return conversationRepository.findByAgentId(agentId).stream()
                .map(conversationMapper::toDomain)
                .toList();
    }
}
