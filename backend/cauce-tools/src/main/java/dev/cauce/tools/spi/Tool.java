package dev.cauce.tools.spi;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolExecutionContext;
import dev.cauce.core.tool.ToolResult;

/**
 * The pluggable contract every tool implements. The orchestrator depends only on this
 * interface; concrete tools (the built-in clock, the business tools, …) register as Spring
 * beans and are discovered by {@link ToolRegistry}. The neutral value objects this contract
 * exchanges ({@link ToolDefinition}, {@link ToolCall}, {@link ToolResult},
 * {@link ToolExecutionContext}) live in cauce-core, so neither the core nor any LLM provider
 * depends on this module.
 *
 * <p><b>Threading:</b> {@link #execute} must be stateless and thread-safe; a single bean
 * serves concurrent calls.
 */
public interface Tool {

    /**
     * The neutral declaration this tool advertises. Its {@link ToolDefinition#name()} is the
     * stable id under which the tool is registered and looked up.
     */
    ToolDefinition definition();

    /**
     * Executes a model-requested call and produces a result correlated by the call's
     * {@link ToolCall#toolCallId()}. The subject of the action — tenant, agent, conversation,
     * interlocutor — comes from {@code context}, never from the call's arguments (ADR 0007, B3).
     * Implementations should not throw for ordinary tool failures: return
     * {@link ToolResult#error} so the failure can be fed back to the model. A malformed argument
     * may be thrown as {@link ToolArguments.InvalidToolArgumentException}, whose message reaches
     * the model; any other exception reaches it only as a sanitized failure (ADR 0005, C6).
     */
    ToolResult execute(ToolCall call, ToolExecutionContext context);

    /**
     * Whether this tool is a lookup: it changes nothing, and calling it again gives what the agent
     * needs now. An agent that elides past lookups (deferred R1) leaves earlier turns' results of
     * such tools out of the model's context. An action is never a lookup: its result (a booking's
     * code, a case's id) is what later turns build on. False unless a tool says otherwise.
     */
    default boolean lookup() {
        return false;
    }
}
