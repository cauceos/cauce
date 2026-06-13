package dev.cauce.core.tool;

/**
 * The structured payload carried by a tool message: either a {@link ToolCall} (the agent
 * asks to run a tool) or a {@link ToolResult} (the tool's output).
 *
 * <p>Both variants reference the same {@code toolCallId}, which correlates a result back to
 * its originating call — every LLM provider requires this correlation id. Pure value-object
 * hierarchy with no framework or provider dependencies.
 */
public sealed interface ToolContent permits ToolCall, ToolResult {

    /** Correlation id linking a call to its result. */
    String toolCallId();

    /** The stable name of the tool being called. */
    String toolName();
}
