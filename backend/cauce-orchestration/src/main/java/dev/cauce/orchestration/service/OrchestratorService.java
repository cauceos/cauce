package dev.cauce.orchestration.service;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageNotFoundException;
import dev.cauce.core.message.MessageRole;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.spi.LlmCredential;
import dev.cauce.llm.spi.LlmProvider;
import dev.cauce.llm.spi.LlmProviderRegistry;
import dev.cauce.llm.spi.SystemDefaultLlmCredential;
import dev.cauce.memory.agent.AgentMapper;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.conversation.ConversationEntity;
import dev.cauce.memory.conversation.ConversationRepository;
import dev.cauce.memory.message.MessageMapper;
import dev.cauce.memory.message.MessageRepository;
import dev.cauce.orchestration.context.ContextBuilder;
import dev.cauce.orchestration.context.LlmInvocationContext;
import dev.cauce.orchestration.exception.InvalidTriggerMessageException;
import dev.cauce.orchestration.exception.LlmProviderNotAvailableException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Synchronous orchestrator: given a USER message it builds the conversation context, invokes
 * the agent's LLM, persists the agent's reply, and advances the conversation. It ties
 * together {@link ContextBuilder}, the cauce-llm provider SPI, and the cauce-memory
 * repositories.
 *
 * <p>Tenant-scoped: the caller sets {@code TenantContext} and {@code RlsContextAspect}
 * propagates it to the database for this {@code @Transactional} method, so every read and
 * write is filtered by hierarchical Row-Level Security. The whole happy path (reads, the LLM
 * call, persisting the agent reply, advancing the conversation) runs in one transaction.
 *
 * <p>On a provider failure the original {@link LlmProviderException} is re-thrown, but the
 * error is first recorded as a SYSTEM message via {@link OrchestrationErrorRecorder} in a
 * separate ({@code REQUIRES_NEW}) transaction so it survives the propagated rollback.
 */
@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);

    private static final String ERROR_PREFIX = "[orchestration_error] ";
    private static final int ERROR_MESSAGE_MAX_LENGTH = 2000;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final AgentRepository agentRepository;
    private final AgentMapper agentMapper;
    private final LlmProviderRegistry llmProviderRegistry;
    private final ContextBuilder contextBuilder;
    private final Environment environment;
    private final OrchestrationErrorRecorder errorRecorder;

    public OrchestratorService(ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               MessageMapper messageMapper,
                               AgentRepository agentRepository,
                               AgentMapper agentMapper,
                               LlmProviderRegistry llmProviderRegistry,
                               ContextBuilder contextBuilder,
                               Environment environment,
                               OrchestrationErrorRecorder errorRecorder) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.messageMapper = messageMapper;
        this.agentRepository = agentRepository;
        this.agentMapper = agentMapper;
        this.llmProviderRegistry = llmProviderRegistry;
        this.contextBuilder = contextBuilder;
        this.environment = environment;
        this.errorRecorder = errorRecorder;
    }

    /**
     * Generates and persists the agent's reply to {@code triggerMessageId} in
     * {@code conversationId}, returning the persisted AGENT message.
     *
     * @throws ConversationNotFoundException if the conversation does not exist or is not visible
     * @throws MessageNotFoundException if the trigger message is not in the conversation
     * @throws InvalidTriggerMessageException if the trigger message is not a USER message
     * @throws AgentNotFoundException if the conversation's agent no longer exists
     * @throws LlmProviderNotAvailableException if no provider is registered for the agent's model
     * @throws LlmProviderException if the provider invocation fails (the error is recorded first)
     */
    @Transactional
    public Message respondToMessage(UUID conversationId, UUID triggerMessageId) {
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

        // Setup errors (unknown model, message too large) propagate as-is: they are not LLM
        // failures and must not be recorded as orchestration errors.
        LlmInvocationContext context =
                contextBuilder.build(messages, agent.modelName(), agent.systemPrompt());

        LlmCredential credential = new SystemDefaultLlmCredential(agent.modelProvider(), environment);
        LlmProvider provider = llmProviderRegistry.getProvider(agent.modelProvider()).orElseThrow(() ->
                new LlmProviderNotAvailableException("No LLM provider available for '"
                        + agent.modelProvider() + "' (is its API key configured?). Available: "
                        + llmProviderRegistry.availableProviders()));

        LlmInvocation invocation = LlmInvocation.builder()
                .modelName(agent.modelName())
                .messages(context.messages())
                .systemPrompt(agent.systemPrompt())
                .tools(List.of())
                .maxTokens(agent.maxResponseTokens())
                .temperature(agent.temperature())
                .credential(credential)
                .build();

        long startNanos = System.nanoTime();
        LlmResponse response;
        try {
            response = provider.invoke(invocation);
        } catch (LlmProviderException e) {
            long latencyMs = elapsedMillis(startNanos);
            log.warn("LLM invocation failed: conversation={} agent={} model={} latencyMs={} error={}",
                    conversationId, agent.id(), agent.modelName(), latencyMs, e.toString(), e);
            errorRecorder.recordError(conversationId, formatError(e));
            throw e;
        }

        long latencyMs = elapsedMillis(startNanos);
        log.info("LLM invocation succeeded: conversation={} agent={} model={} inputTokens={} "
                        + "outputTokens={} totalTokens={} latencyMs={}",
                conversationId, agent.id(), agent.modelName(), response.usage().inputTokens(),
                response.usage().outputTokens(), response.usage().totalTokens(), latencyMs);

        Message agentMessage = Message.from(conversationId, MessageRole.AGENT, response.content());
        Message saved = messageMapper.toDomain(messageRepository.save(messageMapper.toEntity(agentMessage)));
        conversationRepository.touchLastMessageAt(conversationId, saved.createdAt());
        return saved;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String formatError(LlmProviderException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        String truncated = message.length() <= ERROR_MESSAGE_MAX_LENGTH
                ? message
                : message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
        return ERROR_PREFIX + e.getClass().getSimpleName() + ": " + truncated;
    }
}
