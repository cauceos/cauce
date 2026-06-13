package dev.cauce.orchestration.service;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageNotFoundException;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.model.LlmInvocation;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.spi.LlmCredential;
import dev.cauce.llm.spi.LlmProvider;
import dev.cauce.llm.spi.LlmProviderRegistry;
import dev.cauce.llm.spi.SystemDefaultLlmCredential;
import dev.cauce.orchestration.context.ContextBuilder;
import dev.cauce.orchestration.context.LlmInvocationContext;
import dev.cauce.orchestration.exception.InvalidTriggerMessageException;
import dev.cauce.orchestration.exception.LlmProviderNotAvailableException;
import dev.cauce.orchestration.exception.MaxToolIterationsExceededException;
import dev.cauce.orchestration.service.ConversationGateway.LoadedConversation;
import dev.cauce.tools.spi.Tool;
import dev.cauce.tools.spi.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * or fails it as before); exceeding {@link #MAX_TOOL_ITERATIONS} records a SYSTEM error and
 * throws {@link MaxToolIterationsExceededException} (the worker fails the invocation).
 */
@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);

    /**
     * Hard cap on LLM invocations per inbound message (no configuration — YAGNI). The loop runs
     * at most this many rounds; if the model still requests tools on the last one, the invocation
     * fails. This also bounds an agent looping on a broken tool, so no separate error budget is
     * needed.
     */
    static final int MAX_TOOL_ITERATIONS = 10;

    private static final String ERROR_PREFIX = "[orchestration_error] ";
    private static final int ERROR_MESSAGE_MAX_LENGTH = 2000;

    private final ConversationGateway conversationGateway;
    private final LlmProviderRegistry llmProviderRegistry;
    private final ContextBuilder contextBuilder;
    private final ToolRegistry toolRegistry;
    private final Environment environment;
    private final OrchestrationErrorRecorder errorRecorder;

    public OrchestratorService(ConversationGateway conversationGateway,
                               LlmProviderRegistry llmProviderRegistry,
                               ContextBuilder contextBuilder,
                               ToolRegistry toolRegistry,
                               Environment environment,
                               OrchestrationErrorRecorder errorRecorder) {
        this.conversationGateway = conversationGateway;
        this.llmProviderRegistry = llmProviderRegistry;
        this.contextBuilder = contextBuilder;
        this.toolRegistry = toolRegistry;
        this.environment = environment;
        this.errorRecorder = errorRecorder;
    }

    /**
     * Drives the agent's reply to {@code triggerMessageId} in {@code conversationId}, executing
     * any requested tools, and returns the persisted final AGENT message.
     *
     * @throws ConversationNotFoundException if the conversation does not exist or is not visible
     * @throws MessageNotFoundException if the trigger message is not in the conversation
     * @throws InvalidTriggerMessageException if the trigger message is not a USER message
     * @throws AgentNotFoundException if the conversation's agent no longer exists
     * @throws LlmProviderNotAvailableException if no provider is registered for the agent's model
     * @throws LlmProviderException if a provider invocation fails (the error is recorded first)
     * @throws MaxToolIterationsExceededException if the tool-iteration cap is hit
     */
    public Message respondToMessage(UUID conversationId, UUID triggerMessageId) {
        LoadedConversation loaded = conversationGateway.load(conversationId, triggerMessageId);
        Agent agent = loaded.agent();
        List<Message> messages = new ArrayList<>(loaded.messages());

        LlmCredential credential = new SystemDefaultLlmCredential(agent.modelProvider(), environment);
        LlmProvider provider = llmProviderRegistry.getProvider(agent.modelProvider()).orElseThrow(() ->
                new LlmProviderNotAvailableException("No LLM provider available for '"
                        + agent.modelProvider() + "' (is its API key configured?). Available: "
                        + llmProviderRegistry.availableProviders()));
        List<ToolDefinition> toolDefinitions = toolDefinitions();

        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
            LlmInvocationContext context =
                    contextBuilder.build(messages, agent.modelName(), agent.systemPrompt());
            LlmInvocation invocation = LlmInvocation.builder()
                    .modelName(agent.modelName())
                    .messages(context.messages())
                    .systemPrompt(agent.systemPrompt())
                    .tools(toolDefinitions)
                    .maxTokens(agent.maxResponseTokens())
                    .temperature(agent.temperature())
                    .credential(credential)
                    .build();

            LlmResponse response = invoke(provider, invocation, conversationId, agent);

            if (response.toolCalls().isEmpty()) {
                // Final reply: persist the AGENT message and finish (the no-tools path).
                return conversationGateway.append(
                        Message.from(conversationId, MessageRole.AGENT, response.content()));
            }

            // The model may emit text alongside its tool calls; persist it as an AGENT message so
            // it precedes the tool calls when the turn is re-sent to the provider.
            if (!response.content().isBlank()) {
                messages.add(conversationGateway.append(
                        Message.from(conversationId, MessageRole.AGENT, response.content())));
            }
            for (ToolCall call : response.toolCalls()) {
                messages.add(conversationGateway.append(Message.toolCall(conversationId, call)));
            }
            for (ToolCall call : response.toolCalls()) {
                ToolResult result = dispatch(call);
                messages.add(conversationGateway.append(Message.toolResult(conversationId, result)));
            }
        }

        String error = ERROR_PREFIX + "Agent exceeded the maximum of " + MAX_TOOL_ITERATIONS
                + " tool iterations without producing a final reply";
        log.warn("Conversation {} hit the tool-iteration cap ({})", conversationId, MAX_TOOL_ITERATIONS);
        errorRecorder.recordError(conversationId, error);
        throw new MaxToolIterationsExceededException(error);
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
                    e.toString(), e);
            errorRecorder.recordError(conversationId, formatError(e));
            throw e;
        }
    }

    /**
     * Executes one tool call. Tool-level failures (unknown tool, a thrown exception, rejected
     * input) never fail the invocation: they become an errored {@link ToolResult} fed back to the
     * model, which can react.
     */
    private ToolResult dispatch(ToolCall call) {
        Optional<Tool> tool = toolRegistry.getTool(call.toolName());
        if (tool.isEmpty()) {
            log.warn("Agent requested unknown tool '{}'", call.toolName());
            return ToolResult.error(call.toolCallId(), call.toolName(),
                    "Unknown tool '" + call.toolName() + "'");
        }
        try {
            return tool.get().execute(call);
        } catch (RuntimeException e) {
            log.warn("Tool '{}' execution failed: {}", call.toolName(), e.toString(), e);
            return ToolResult.error(call.toolCallId(), call.toolName(),
                    "Tool execution failed: " + e.getMessage());
        }
    }

    /** All registered tools (global scoping; per-agent scoping is deferred). */
    private List<ToolDefinition> toolDefinitions() {
        return toolRegistry.availableTools().stream()
                .map(name -> toolRegistry.requireTool(name).definition())
                .toList();
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
