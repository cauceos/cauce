package dev.cauce.api.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolContent;
import dev.cauce.core.tool.ToolResult;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * API representation of a {@link Message}. Decoupled from the domain type so the wire contract
 * can evolve independently. {@code role} serialises as its name ({@code USER}/{@code AGENT}/
 * {@code SYSTEM}/{@code TOOL_CALL}/{@code TOOL_RESULT}); other fields are snake_case via the
 * global Jackson naming strategy.
 *
 * <p>{@code toolContent} carries the structured tool payload for TOOL_CALL / TOOL_RESULT
 * messages and is omitted entirely for text messages. This is additive — every pre-existing
 * field keeps its name and shape.
 *
 * <p>Deliberately absent: an {@code invocation_id}. The message row carries no link to its
 * invocation (the only link is one-way, {@code pending_invocations.trigger_message_id ->
 * messages.id}, and only for the USER trigger), so exposing it here would require a fabricated
 * join. It stays a named gap until the row carries the link.
 */
public record MessageResponse(
        UUID id,
        MessageRole role,
        String content,
        @JsonInclude(JsonInclude.Include.NON_NULL) ToolContentResponse toolContent,
        Instant createdAt) {

    public static MessageResponse from(Message message) {
        ToolContentResponse toolContent = message.toolContent()
                .map(ToolContentResponse::from)
                .orElse(null);
        return new MessageResponse(message.id(), message.role(), message.content(), toolContent,
                message.createdAt());
    }

    /**
     * Structured tool payload, mirroring the persisted jsonb. A TOOL_CALL carries
     * {@code input}; a TOOL_RESULT carries {@code output} and {@code isError}. The other
     * variant's fields are null and omitted (NON_NULL), so a call and a result each serialise
     * only their own keys.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolContentResponse(
            String toolCallId,
            String toolName,
            Map<String, Object> input,
            String output,
            Boolean isError) {

        static ToolContentResponse from(ToolContent toolContent) {
            return switch (toolContent) {
                case ToolCall call -> new ToolContentResponse(
                        call.toolCallId(), call.toolName(), call.input(), null, null);
                case ToolResult result -> new ToolContentResponse(
                        result.toolCallId(), result.toolName(), null, result.output(), result.isError());
            };
        }
    }
}
