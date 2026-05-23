package dev.cauce.llm.model;

import java.util.List;
import java.util.Objects;

/**
 * A provider-independent completion result. Immutable.
 *
 * <p>{@code toolCalls} is part of the contract from v1.0 but is always empty until the
 * Tool entity exists.
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
