package dev.cauce.orchestration.service;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageNotFoundException;
import dev.cauce.core.message.MessageRole;
import dev.cauce.memory.agent.AgentMapper;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.conversation.ConversationEntity;
import dev.cauce.memory.conversation.ConversationRepository;
import dev.cauce.memory.message.MessageMapper;
import dev.cauce.memory.message.MessageRepository;
import dev.cauce.orchestration.exception.InvalidTriggerMessageException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped database boundary for the orchestrator's agentic loop. Each method is a short
 * {@code @Transactional} unit advised by {@code RlsContextAspect}, so the loop driver
 * ({@link OrchestratorService}) runs the (non-transactional) LLM calls <em>between</em> them
 * without holding a database connection, and each round's tool messages commit independently —
 * visible over the messages API while the loop continues.
 *
 * <p>This is a separate bean on purpose: a self-invoked {@code @Transactional} method on the
 * orchestrator would bypass the Spring proxy and silently run with no transaction (and no RLS).
 */
@Service
public class ConversationGateway {

    private static final Logger log = LoggerFactory.getLogger(ConversationGateway.class);

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final AgentRepository agentRepository;
    private final AgentMapper agentMapper;

    public ConversationGateway(ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               MessageMapper messageMapper,
                               AgentRepository agentRepository,
                               AgentMapper agentMapper) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.messageMapper = messageMapper;
        this.agentRepository = agentRepository;
        this.agentMapper = agentMapper;
    }

    /** A conversation loaded for orchestration: its agent and its chronological message history. */
    public record LoadedConversation(Agent agent, List<Message> messages) {
    }

    /**
     * Loads the conversation's agent and message history, validating that {@code triggerMessageId}
     * is a USER message within it.
     *
     * @throws ConversationNotFoundException if the conversation does not exist or is not visible
     * @throws MessageNotFoundException if the trigger message is not in the conversation
     * @throws InvalidTriggerMessageException if the trigger message is not a USER message
     * @throws AgentNotFoundException if the conversation's agent no longer exists
     */
    @Transactional
    public LoadedConversation load(UUID conversationId, UUID triggerMessageId) {
        ConversationEntity conversation = conversationRepository.findById(conversationId).orElseThrow(() ->
                new ConversationNotFoundException("No conversation found for id " + conversationId));

        // Single read of the history: serves both trigger validation and context building.
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                .stream().map(messageMapper::toDomain).toList();

        Message trigger = messages.stream()
                .filter(message -> message.id().equals(triggerMessageId))
                .findFirst()
                .orElseThrow(() -> new MessageNotFoundException(
                        "No message " + triggerMessageId + " found in conversation " + conversationId));
        if (trigger.role() != MessageRole.USER) {
            throw new InvalidTriggerMessageException("Trigger message " + triggerMessageId
                    + " must have role USER but was " + trigger.role());
        }

        Agent agent = agentRepository.findById(conversation.getAgentId())
                .map(agentMapper::toDomain)
                .orElseThrow(() -> {
                    log.warn("Conversation {} references agent {} that does not exist "
                            + "(state inconsistency)", conversationId, conversation.getAgentId());
                    return new AgentNotFoundException(
                            "No agent found for id " + conversation.getAgentId());
                });

        return new LoadedConversation(agent, messages);
    }

    /**
     * Persists {@code message} (a text, tool-call, or tool-result message) and advances the
     * conversation's {@code lastMessageAt}, committing in its own transaction so the message is
     * visible immediately while the loop continues.
     */
    @Transactional
    public Message append(Message message) {
        Message saved = messageMapper.toDomain(messageRepository.save(messageMapper.toEntity(message)));
        conversationRepository.touchLastMessageAt(message.conversationId(), saved.createdAt());
        return saved;
    }
}
