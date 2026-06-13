package dev.cauce.core.tool;

import java.util.Map;

/**
 * A tool invocation requested by the agent: which tool ({@code toolName}), the correlation
 * id ({@code toolCallId}), and the {@code input} arguments.
 *
 * <p>{@code input} is an opaque JSON-object-shaped map; validating it against the tool's
 * {@link ToolDefinition#inputSchema()} is the caller's concern, not the value object's.
 */
public record ToolCall(String toolCallId, String toolName, Map<String, Object> input)
        implements ToolContent {

    public ToolCall {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
