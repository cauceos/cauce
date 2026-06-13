package dev.cauce.llm.model;

import dev.cauce.core.tool.ToolCall;
import java.util.List;
import java.util.Objects;

/**
 * A provider-independent completion result. Immutable.
 *
 * <p>{@code content} (text) and {@code toolCalls} can coexist: a model may emit text and
 * request one or more tool calls in the same turn. {@code toolCalls} defaults to empty for a
 * plain text completion; {@code finishReason} is {@link FinishReason#TOOL_USE} when the model
 * asked to call a tool.
 */
public record LlmResponse(
        String content,
        List<ToolCall> toolCalls,
        FinishReason finishReason,
        LlmUsage usage) {

    public LlmResponse {
        Objects.requireNonNull(content, "content");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        Objects.requireNonNull(finishReason, "finishReason");
        Objects.requireNonNull(usage, "usage");
    }
}
