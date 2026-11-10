package dev.cauce.core.tool;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of executing a {@link ToolCall}: the correlation {@code toolCallId}, the
 * {@code toolName}, the {@code output} payload (opaque text the model reads back — typically
 * JSON or plain text), an {@code isError} flag, and the catalog refs the result asks to show as
 * product cards in the agent's reply ({@code productCards}, ADR 0014, WC8): only a tool that shows
 * products names them, after checking each ref against the catalog.
 *
 * <p>Prefer the {@link #success} / {@link #error} / {@link #showing} factories over the canonical
 * constructor. {@code output} may be empty (a tool can legitimately produce no text) but never null.
 */
public record ToolResult(String toolCallId, String toolName, String output, boolean isError,
                         List<String> productCards) implements ToolContent {

    /** A result that shows no product cards: every tool's but one. */
    public ToolResult(String toolCallId, String toolName, String output, boolean isError) {
        this(toolCallId, toolName, output, isError, List.of());
    }

    public ToolResult {
        productCards = productCards == null ? List.of() : List.copyOf(productCards);
        if (isError && !productCards.isEmpty()) {
            throw new IllegalArgumentException("a failed result shows no product cards");
        }
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

    /** A successful result that shows the items {@code refs} as product cards in the reply. */
    public static ToolResult showing(String toolCallId, String toolName, String output, List<String> refs) {
        return new ToolResult(toolCallId, toolName, output, false, refs);
    }

    /** A failed execution; {@code message} is the error text surfaced to the model. */
    public static ToolResult error(String toolCallId, String toolName, String message) {
        return new ToolResult(toolCallId, toolName, message, true);
    }
}
