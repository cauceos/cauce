package dev.cauce.core.tool;

import java.util.Objects;

/**
 * The outcome of executing a {@link ToolCall}: the correlation {@code toolCallId}, the
 * {@code toolName}, the {@code output} payload (opaque text the model reads back — typically
 * JSON or plain text), and an {@code isError} flag.
 *
 * <p>Prefer the {@link #success} / {@link #error} factories over the canonical constructor.
 * {@code output} may be empty (a tool can legitimately produce no text) but never null.
 */
public record ToolResult(String toolCallId, String toolName, String output, boolean isError)
        implements ToolContent {

    public ToolResult {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        Objects.requireNonNull(output, "output");
    }

    /** A successful result carrying the tool's {@code output}. */
    public static ToolResult success(String toolCallId, String toolName, String output) {
        return new ToolResult(toolCallId, toolName, output, false);
    }

    /** A failed execution; {@code message} is the error text surfaced to the model. */
    public static ToolResult error(String toolCallId, String toolName, String message) {
        return new ToolResult(toolCallId, toolName, message, true);
    }
}
