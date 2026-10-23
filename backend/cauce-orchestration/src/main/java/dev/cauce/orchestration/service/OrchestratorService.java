package dev.cauce.orchestration.service;

import dev.cauce.core.LogKeys;
import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.conversation.AgentReplyDispatcher;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageNotFoundException;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolExecutionContext;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.spi.LlmCredential;
import dev.cauce.llm.spi.LlmProvider;
import dev.cauce.llm.spi.LlmProviderRegistry;
import dev.cauce.llm.spi.SystemDefaultLlmCredential;
import dev.cauce.orchestration.LogSafe;
import dev.cauce.orchestration.audit.ConductAuditEvents;
import dev.cauce.orchestration.context.ContextBuilder;
import dev.cauce.orchestration.context.LlmInvocationContext;
import dev.cauce.orchestration.events.ContextAssembled;
import dev.cauce.orchestration.events.InvocationCompleted;
import dev.cauce.orchestration.events.LlmFailed;
import dev.cauce.orchestration.events.LlmInvoked;
import dev.cauce.orchestration.events.LlmResponded;
import dev.cauce.orchestration.events.ToolCallRequested;
import dev.cauce.orchestration.events.ToolExecuted;
import dev.cauce.orchestration.exception.EmptyReplyException;
import dev.cauce.orchestration.exception.InvalidTriggerMessageException;
import dev.cauce.orchestration.exception.LlmProviderNotAvailableException;
import dev.cauce.orchestration.exception.MaxToolIterationsExceededException;
import dev.cauce.orchestration.exception.SpendLimitReachedException;
import dev.cauce.orchestration.exception.UnpricedModelException;
import dev.cauce.orchestration.service.ConversationGateway.LoadedConversation;
import dev.cauce.orchestration.tracing.MessageTracing;
import dev.cauce.orchestration.usage.LlmUsageRecord;
import dev.cauce.orchestration.usage.LlmUsageRecorder;
import dev.cauce.orchestration.usage.SpendGuard;
import dev.cauce.tools.spi.Tool;
import dev.cauce.tools.spi.ToolArguments.InvalidToolArgumentException;
import dev.cauce.tools.spi.ToolRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Bounded agentic loop: given a USER message it builds the conversation context (with the
 * available tools), invokes the agent's LLM, and — while the model asks for tools — executes
 * them and feeds the results back, re-invoking until the model produces a final reply or the
 * iteration cap is hit. It ties together {@link ConversationGateway} (persistence under RLS),
 * {@link ContextBuilder}, the cauce-llm provider SPI, and the cauce-tools {@link ToolRegistry}.
 *
 * <p><b>No-tools path is unchanged:</b> when the model replies with text and no tool calls, the
 * loop runs exactly one round and persists a single AGENT message — byte-identical in behaviour
 * to the prior single-shot orchestrator.
 *
 * <p><b>Transactions:</b> this method is deliberately not {@code @Transactional}. Reads and
 * writes go through the short {@code @Transactional} methods of {@link ConversationGateway}, so
 * the LLM calls run with no database connection held and each round's tool messages commit
 * independently (visible over the messages API while the loop continues).
 *
 * <p><b>Errors:</b> a tool failure (it throws, is unknown, or its input is rejected) becomes a
 * TOOL_RESULT with {@code is_error} fed back to the model — the invocation does not fail. A
 * provider failure ({@link LlmProviderException}) is recorded and re-thrown (the worker retries
 * or fails it as before); exceeding the per-message cap ({@link AgenticLoopProperties}) records a SYSTEM error and
 * throws {@link MaxToolIterationsExceededException} (the worker fails the invocation).
 *
 * <p><b>Spend limit:</b> before the first model call, {@link SpendGuard} compares the client's
 * spend this month with its limit (ADR 0010, S6): a reached limit, or a limit with the agent's model
 * unpriced (S7), records a SYSTEM error and throws, so the worker fails the invocation and the
 * person gets the fallback reply. Checked once per invocation, never between rounds.
 *
 * <p><b>Usage:</b> every provider response writes one row to the per-tenant usage ledger via
 * {@link LlmUsageRecorder}, synchronously and before the {@code LlmResponded} event, so the
 * billing fact is committed before any consumer can observe the call. A failed usage INSERT
 * fails the invocation — deliberate: usage is never lost silently.
 *
 * <p><b>Events:</b> each step publishes its lifecycle event (see
 * {@code dev.cauce.orchestration.events}) synchronously, always outside any transaction and
 * after the step's messages have committed. Purely observational: emission changes no
 * behaviour.
 *
 * <p><b>Outbound delivery:</b> after the final AGENT message commits, the loop hands it to the
 * {@link AgentReplyDispatcher} port (if an implementation is on the classpath) so the channel
 * layer can deliver it back through the conversation's channel of origin. Deliberately an
 * explicit port, NOT an event consumer — the event stream stays observational. Best-effort and
 * guarded: dispatch failures are logged and never fail the invocation.
 */
@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);

    /**
     * What a failed tool returns to the model (ADR 0005, C6): sanitized, never the exception
     * message or a stack trace, which can carry internal detail. The full error is logged.
     */
    static final String TOOL_FAILURE_OUTPUT = "The tool failed to run. No further detail is available.";

    private static final String ERROR_PREFIX = "[orchestration_error] ";
    private static final int ERROR_MESSAGE_MAX_LENGTH = 2000;

    private final ConversationGateway conversationGateway;
    private final LlmProviderRegistry llmProviderRegistry;
    private final ContextBuilder contextBuilder;
    private final ToolRegistry toolRegistry;
    private final Environment environment;
    private final OrchestrationErrorRecorder errorRecorder;
    private final LlmUsageRecorder usageRecorder;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<AgentReplyDispatcher> replyDispatcher;
    private final AgenticLoopProperties loopProperties;
    private final SpendGuard spendGuard;
    private final MessageTracing tracing;
    private final Clock clock;

    public OrchestratorService(ConversationGateway conversationGateway,
                               LlmProviderRegistry llmProviderRegistry,
                               ContextBuilder contextBuilder,
                               ToolRegistry toolRegistry,
                               Environment environment,
                               OrchestrationErrorRecorder errorRecorder,
                               LlmUsageRecorder usageRecorder,
                               ApplicationEventPublisher eventPublisher,
                               ObjectProvider<AgentReplyDispatcher> replyDispatcher,
                               AgenticLoopProperties loopProperties,
                               SpendGuard spendGuard,
                               MessageTracing tracing,
                               Clock clock) {
        this.conversationGateway = conversationGateway;
        this.llmProviderRegistry = llmProviderRegistry;
        this.contextBuilder = contextBuilder;
        this.toolRegistry = toolRegistry;
        this.environment = environment;
        this.errorRecorder = errorRecorder;
        this.usageRecorder = usageRecorder;
        this.eventPublisher = eventPublisher;
        this.replyDispatcher = replyDispatcher;
        this.loopProperties = loopProperties;
        this.spendGuard = spendGuard;
        this.tracing = tracing;
        this.clock = clock;
    }

    /**
     * From the person's message to the reply (ADR 0011, M1): both stamped by the database clock when
     * appended (ADR 0008), so the turn's latency never mixes clocks. Null when a stamp is missing.
     */
    static Duration turnLatency(List<Message> messages, UUID triggerMessageId, Message reply) {
        return messages.stream()
                .filter(message -> message.id().equals(triggerMessageId))
                .map(Message::createdAt)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .filter(asked -> reply.createdAt() != null)
                .map(asked -> Duration.between(asked, reply.createdAt()))
                .orElse(null);
    }

    /**
     * Drives the agent's reply to {@code triggerMessageId} in {@code conversationId}, executing
     * any requested tools, and returns the persisted final AGENT message.
     *
     * @param invocationId the queued invocation being processed; carried on every lifecycle
     *     event so consumers can correlate the stream (it is not read for anything else)
     * @throws ConversationNotFoundException if the conversation does not exist or is not visible
     * @throws MessageNotFoundException if the trigger message is not in the conversation
     * @throws InvalidTriggerMessageException if the trigger message is not a USER message
     * @throws AgentNotFoundException if the conversation's agent no longer exists
     * @throws LlmProviderNotAvailableException if no provider is registered for the agent's model
     * @throws LlmProviderException if a provider invocation fails (the error is recorded first)
     * @throws MaxToolIterationsExceededException if the tool-iteration cap is hit
     * @throws SpendLimitReachedException if the client spent its monthly limit
     * @throws UnpricedModelException if the client has a limit and the agent's model no price
     */
    public Message respondToMessage(UUID invocationId, UUID conversationId, UUID triggerMessageId) {
        LoadedConversation loaded = conversationGateway.load(conversationId, triggerMessageId);
        Agent agent = loaded.agent();
        MDC.put(LogKeys.AGENT_ID, agent.id().toString()); // ADR 0011, L1; the worker clears it
        List<Message> messages = new ArrayList<>(loaded.messages());
        checkSpend(agent, conversationId);

        LlmCredential credential = new SystemDefaultLlmCredential(agent.modelProvider(), environment);
        LlmProvider provider = llmProviderRegistry.getProvider(agent.modelProvider()).orElseThrow(() ->
                new LlmProviderNotAvailableException("No LLM provider available for '"
                        + agent.modelProvider() + "' (is its API key configured?). Available: "
                        + llmProviderRegistry.availableProviders()));
        List<ToolDefinition> toolDefinitions = toolDefinitions(agent);
        // Deferred R1, behind a per-agent switch: earlier turns' lookups the agent can repeat.
        Set<String> elidedLookups = agent.elidesPastLookups() ? toolRegistry.lookupTools() : Set.of();
        // ADR 0007, B3: who the tools act for is decided here, never by the model.
        ToolExecutionContext toolContext = new ToolExecutionContext(agent.tenantId(), agent.id(),
                conversationId, loaded.conversation().identityId(), triggerMessageId, invocationId);

        // ADR 0005, C6: the cap is per inbound message. Rounds an earlier attempt of this
        // invocation already spent (its tool calls are persisted after the trigger) count.
        int maxIterations = loopProperties.getMaxIterations();
        int alreadyUsed = roundsAlreadyUsed(loaded.messages(), triggerMessageId);
        for (int iteration = alreadyUsed; iteration < maxIterations; iteration++) {
            LlmInvocationContext context =
                    contextBuilder.build(messages, agent.modelName(), agent.systemPrompt(), elidedLookups);
            eventPublisher.publishEvent(new ContextAssembled(invocationId, iteration,
                    agent.modelName(), context.messages().size(), context.estimatedTokens(),
                    context.contextWindowLimit(), Instant.now()));
            LlmInvocation invocation = LlmInvocation.builder()
                    .modelName(agent.modelName())
                    .messages(context.messages())
                    .systemPrompt(agent.systemPrompt())
                    .tools(toolDefinitions)
                    .maxTokens(agent.maxResponseTokens())
                    .temperature(agent.temperature().orElse(null))
                    .credential(credential)
                    // The agent's tools and prompt are fixed and the context only grows: a
                    // provider that caches prompt prefixes may cache them (billing only).
                    .cacheablePrefix(true)
                    .cacheRetention(agent.promptCacheRetention())
                    .build();

            eventPublisher.publishEvent(new LlmInvoked(invocationId, provider.id(),
                    agent.modelName(), iteration, Instant.now()));
            LlmResponse response;
            // ADR 0011, T3: the model call's span says which model and what it cost, never what was said.
            try (MessageTracing.TracedSpan span = tracing.childSpan("cauce.llm.call")
                    .tag("provider", provider.id()).tag("model", agent.modelName()).tag("round", iteration)
                    .tag("tenant_id", agent.tenantId().toString()).tag("agent_id", agent.id().toString())) {
                try {
                    response = invoke(provider, invocation, conversationId, agent);
                } catch (RuntimeException e) {
                    span.error(e);
                    eventPublisher.publishEvent(new LlmFailed(invocationId, provider.id(), agent.modelName(),
                            iteration, e.getClass().getSimpleName(), Instant.now()));
                    throw e;
                }
                span.tag("input_tokens", response.usage().inputTokens())
                        .tag("output_tokens", response.usage().outputTokens())
                        .tag("cache_read_tokens", response.usage().cacheReadTokens())
                        .tag("cache_write_tokens", response.usage().cacheWriteTokens())
                        .tag("finish_reason", response.finishReason().name())
                        .tag("outcome", "ok");
            }
            // Billing fact: committed in its own short transaction before the event is
            // observable; a failure here fails the invocation rather than losing usage.
            usageRecorder.record(LlmUsageRecord.create(agent.tenantId(), agent.id(),
                    conversationId, invocationId, provider.id(), agent.modelName(), iteration,
                    response.usage(), response.finishReason().name(), clock.instant()));
            eventPublisher.publishEvent(new LlmResponded(invocationId, provider.id(),
                    agent.modelName(), iteration, response.finishReason().name(),
                    response.usage().inputTokens(), response.usage().outputTokens(),
                    response.usage().totalTokens(), Instant.now()));

            if (response.toolCalls().isEmpty()) {
                if (response.content().isBlank()) {
                    // ADR 0005, C1: an empty final reply is a failure, never a message.
                    String error = ERROR_PREFIX + "The model ended its turn with an empty reply";
                    log.warn("Conversation {} got an empty final reply from the model", conversationId);
                    errorRecorder.recordError(conversationId, error);
                    throw new EmptyReplyException(error);
                }
                // Final reply: persist the AGENT message and finish. The append is the
                // terminal conduct fact, so the conduct.agent.responded audit record (with
                // finish_reason and rounds — the completion record; there is no separate
                // COMPLETED audit event) commits in the SAME transaction as the message.
                // The message id is minted client-side, so the event is complete up front.
                Message reply = Message.from(conversationId, MessageRole.AGENT, response.content());
                Message finalMessage = conversationGateway.appendAudited(reply,
                        ConductAuditEvents.agentResponded(agent.tenantId(), invocationId,
                                agent.id(), reply, response.finishReason().name(), iteration + 1));
                eventPublisher.publishEvent(new InvocationCompleted(invocationId, agent.tenantId(), agent.id(),
                        iteration + 1, finalMessage.id(), turnLatency(loaded.messages(), triggerMessageId, finalMessage),
                        Instant.now()));
                dispatchReply(loaded.conversation(), finalMessage);
                return finalMessage;
            }

            // The model may emit text alongside its tool calls; persist it as an AGENT message so
            // it precedes the tool calls when the turn is re-sent to the provider.
            if (!response.content().isBlank()) {
                messages.add(conversationGateway.append(
                        Message.from(conversationId, MessageRole.AGENT, response.content())));
            }
            // ADR 0005, C5: each tool call and each result commits with its audit record.
            for (ToolCall call : response.toolCalls()) {
                Message callMessage = Message.toolCall(conversationId, call);
                messages.add(conversationGateway.appendAudited(callMessage, ConductAuditEvents.toolCalled(
                        agent.tenantId(), invocationId, agent.id(), callMessage, call, iteration)));
                eventPublisher.publishEvent(new ToolCallRequested(invocationId, iteration,
                        call.toolName(), call.toolCallId(), Instant.now()));
            }
            for (ToolCall call : response.toolCalls()) {
                long dispatchStartNanos = System.nanoTime();
                ToolResult result;
                try (MessageTracing.TracedSpan span = tracing.childSpan("cauce.tool.call")
                        .tag("tool", call.toolName()).tag("round", iteration)) {
                    result = dispatch(call, agent, toolContext);
                    span.tag("outcome", result.isError() ? "error" : "ok");
                }
                long dispatchMillis = elapsedMillis(dispatchStartNanos);
                Message resultMessage = Message.toolResult(conversationId, result);
                messages.add(conversationGateway.appendAudited(resultMessage, ConductAuditEvents.toolReturned(
                        agent.tenantId(), invocationId, agent.id(), resultMessage, result, iteration,
                        dispatchMillis)));
                eventPublisher.publishEvent(new ToolExecuted(invocationId, iteration,
                        call.toolName(), call.toolCallId(), result.isError(), dispatchMillis,
                        Instant.now()));
            }
        }

        String error = ERROR_PREFIX + "Agent exceeded the maximum of " + maxIterations
                + " tool iterations without producing a final reply";
        log.warn("Conversation {} hit the tool-iteration cap ({})", conversationId, maxIterations);
        errorRecorder.recordError(conversationId, error);
        throw new MaxToolIterationsExceededException(error);
    }

    /** ADR 0010, S6, S7: the client's spend limit, checked before the model is called at all. */
    private void checkSpend(Agent agent, UUID conversationId) {
        switch (spendGuard.check(agent)) {
            case ALLOWED -> {
            }
            case LIMIT_REACHED -> {
                String error = ERROR_PREFIX + "The client's monthly spend limit is reached";
                errorRecorder.recordError(conversationId, error);
                throw new SpendLimitReachedException(error);
            }
            case UNPRICED_MODEL -> {
                String error = ERROR_PREFIX + "The client has a spend limit and the model '" + agent.modelProvider()
                        + "/" + agent.modelName() + "' has no price in force";
                errorRecorder.recordError(conversationId, error);
                throw new UnpricedModelException(error);
            }
        }
    }

    /**
     * Hands the committed final reply to the outbound dispatch port (channel delivery), if an
     * implementation is present. Explicit-port trigger by design: the event stream stays
     * observational. Guarded so a dispatcher bug can never fail an invocation whose reply is
     * already persisted — the port contract is best-effort and non-blocking (the channel layer
     * runs the actual delivery on its own executor).
     */
    private void dispatchReply(Conversation conversation, Message finalMessage) {
        AgentReplyDispatcher dispatcher = replyDispatcher.getIfAvailable();
        if (dispatcher == null) {
            return;
        }
        try {
            dispatcher.dispatchAgentReply(conversation, finalMessage);
        } catch (RuntimeException e) {
            log.warn("Outbound dispatch failed for conversation {} (reply {} stays readable "
                    + "via the messages API): {}", conversation.id(), finalMessage.id(),
                    e.toString(), e);
        }
    }

    private LlmResponse invoke(LlmProvider provider, LlmInvocation invocation,
                              UUID conversationId, Agent agent) {
        long startNanos = System.nanoTime();
        try {
            LlmResponse response = provider.invoke(invocation);
            log.info("LLM invocation succeeded: conversation={} agent={} model={} inputTokens={} "
                            + "outputTokens={} totalTokens={} latencyMs={} toolCalls={}",
                    conversationId, agent.id(), agent.modelName(), response.usage().inputTokens(),
                    response.usage().outputTokens(), response.usage().totalTokens(),
                    elapsedMillis(startNanos), response.toolCalls().size());
            return response;
        } catch (LlmProviderException e) {
            log.warn("LLM invocation failed: conversation={} agent={} model={} latencyMs={} error={}",
                    conversationId, agent.id(), agent.modelName(), elapsedMillis(startNanos),
                    LogSafe.describe(e));
            log.debug("LLM invocation failure detail", e);
            errorRecorder.recordError(conversationId, formatError(e));
            throw e;
        }
    }

    /**
     * Executes one tool call. Tool-level failures (unknown tool, a thrown exception, rejected
     * input) never fail the invocation: they become an errored {@link ToolResult} fed back to the
     * model, which can react.
     */
    private ToolResult dispatch(ToolCall call, Agent agent, ToolExecutionContext context) {
        // ADR 0005, C4: a tool outside the agent's grant is, to the model, an unknown tool.
        Optional<Tool> tool = agent.toolNames().contains(call.toolName())
                ? toolRegistry.getTool(call.toolName())
                : Optional.empty();
        if (tool.isEmpty()) {
            log.warn("Agent requested unknown tool '{}'", call.toolName());
            return ToolResult.error(call.toolCallId(), call.toolName(),
                    "Unknown tool '" + call.toolName() + "'");
        }
        try {
            return tool.get().execute(call, context);
        } catch (InvalidToolArgumentException e) {
            // The tool's own account of a malformed argument: written for the model to correct.
            return ToolResult.error(call.toolCallId(), call.toolName(), "Invalid arguments: " + e.getMessage());
        } catch (RuntimeException e) {
            // A tool's exception may echo its arguments: its type only (ADR 0011, L2).
            log.warn("Tool '{}' execution failed: {}", call.toolName(), LogSafe.type(e));
            log.debug("Tool failure detail", e);
            return ToolResult.error(call.toolCallId(), call.toolName(), TOOL_FAILURE_OUTPUT);
        }
    }

    /**
     * The LLM rounds already spent on {@code triggerMessageId}: each non-final round persisted
     * one batch of TOOL_CALL messages after the trigger, so the batches (maximal runs of
     * consecutive TOOL_CALL messages) are the rounds. Zero on a first attempt.
     */
    static int roundsAlreadyUsed(List<Message> history, UUID triggerMessageId) {
        int rounds = 0;
        boolean afterTrigger = false;
        boolean inBatch = false;
        for (Message message : history) {
            if (!afterTrigger) {
                afterTrigger = message.id().equals(triggerMessageId);
                continue;
            }
            boolean toolCall = message.role() == MessageRole.TOOL_CALL;
            if (toolCall && !inBatch) {
                rounds++;
            }
            inBatch = toolCall;
        }
        return rounds;
    }

    /**
     * The tools offered to the model: exactly the agent's grant (ADR 0005, C4), empty by default.
     * A granted name no longer registered (a deployment dropped the tool) is left out with a WARN.
     */
    private List<ToolDefinition> toolDefinitions(Agent agent) {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (String name : agent.toolNames()) {
            Optional<Tool> tool = toolRegistry.getTool(name);
            if (tool.isPresent()) {
                definitions.add(tool.get().definition());
            } else {
                log.warn("Agent {} is granted tool '{}', which is not registered; not offered", agent.id(), name);
            }
        }
        return List.copyOf(definitions);
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
