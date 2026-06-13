package dev.cauce.llm.model;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolContent;
import dev.cauce.core.tool.ToolResult;
import java.util.Objects;

/**
 * A single message in the neutral model. Immutable.
 *
 * <p>A text message carries a {@link LlmRole role} and verbatim {@code content} (non-null,
 * possibly empty). A tool message additionally carries structured {@link ToolContent}: a
 * {@link ToolCall} (the model requested a tool call; role {@code ASSISTANT}) or a
 * {@link ToolResult} (a tool's output fed back to the model; role {@code USER}).
 *
 * <p>The sequence is deliberately <b>flat</b> — one message per call and one per result — and
 * mirrors the persisted {@code Message} aggregate. Each provider adapter assembles the
 * grouped wire format its API expects (Anthropic groups tool_result blocks into a user
 * message; OpenAI emits one {@code role:"tool"} message per result), so there is no neutral
 * "already-grouped" representation.
 */
public record LlmMessage(LlmRole role, String content, ToolContent toolContent) {

    public LlmMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    /** A text message with no tool content. */
    public LlmMessage(LlmRole role, String content) {
        this(role, content, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(LlmRole.USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(LlmRole.ASSISTANT, content);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(LlmRole.SYSTEM, content);
    }

    /** A message carrying the model's request to call a tool. */
    public static LlmMessage toolCall(ToolCall call) {
        return new LlmMessage(LlmRole.ASSISTANT, "", Objects.requireNonNull(call, "call"));
    }

    /** A message carrying a tool's result fed back to the model. */
    public static LlmMessage toolResult(ToolResult result) {
        return new LlmMessage(LlmRole.USER, "", Objects.requireNonNull(result, "result"));
    }
}
