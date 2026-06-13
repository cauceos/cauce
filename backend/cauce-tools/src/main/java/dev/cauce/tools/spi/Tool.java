package dev.cauce.tools.spi;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolResult;

/**
 * The pluggable contract every tool implements. The orchestrator depends only on this
 * interface; concrete tools (the built-in clock, future HTTP/DB/calendar tools, …) register
 * as Spring beans and are discovered by {@link ToolRegistry}. The neutral value objects this
 * contract exchanges ({@link ToolDefinition}, {@link ToolCall}, {@link ToolResult}) live in
 * cauce-core, so neither the core nor any LLM provider depends on this module.
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
     * {@link ToolCall#toolCallId()}. Implementations should not throw for ordinary tool
     * failures: return {@link ToolResult#error} so the failure can be fed back to the model.
     */
    ToolResult execute(ToolCall call);
}
