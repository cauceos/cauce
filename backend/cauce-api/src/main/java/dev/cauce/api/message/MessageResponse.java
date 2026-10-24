package dev.cauce.api.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.cauce.api.invocation.FailureReason;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessagePart;
import dev.cauce.core.message.MessageRole;
import dev.cauce.orchestration.events.InvocationFailureType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API representation of a {@link Message}. Decoupled from the domain type so the wire contract
 * can evolve independently. {@code role} serialises as its name ({@code USER}/{@code AGENT}/
 * {@code SYSTEM}/{@code TOOL_CALL}/{@code TOOL_RESULT}); other fields are snake_case via the
 * global Jackson naming strategy.
 *
 * <p>{@code parts} is the message as stored, an ordered list of typed parts (ADR 0004, decision
 * 2): a text message has text parts, a tool message its one tool part. {@code content} is derived
 * from them, for readers that want only text: the text itself, or a tool message's rendering (the
 * tool name for a call, the output for a result). {@code tool_content} is gone: its payload is the
 * tool part (CHANGELOG, 2026-10-24).
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
        List<PartResponse> parts,
        @JsonInclude(JsonInclude.Include.NON_NULL) FailureReason failureReason,
        Instant createdAt) {

    public static MessageResponse from(Message message) {
        // A FALLBACK carries the public reason only, the invocation endpoint's vocabulary
        // (ADR 0005, C3); the internal type it is stored as never reaches the wire.
        FailureReason failureReason = message.failureType()
                .map(type -> FailureReason.from(InvocationFailureType.valueOf(type)))
                .orElse(null);
        return new MessageResponse(message.id(), message.role(), message.content(),
                message.parts().stream().map(PartResponse::from).toList(), failureReason, message.createdAt());
    }

    /**
     * One part, with its {@code type} and only that type's keys (NON_NULL): {@code text} for a
     * text part; {@code media_type} and, once known, {@code size_bytes}, {@code sha256},
     * {@code duration_seconds} and {@code transcript} for a voice note (ADR 0012) — never the
     * provider's reference to its bytes, a handle for the channel adapter only;
     * {@code tool_call_id}, {@code tool_name} and {@code input} for a tool call;
     * {@code tool_call_id}, {@code tool_name}, {@code output} and {@code is_error} for a result.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PartResponse(
            String type,
            String text,
            String mediaType,
            Long sizeBytes,
            String sha256,
            Integer durationSeconds,
            String transcript,
            String toolCallId,
            String toolName,
            Map<String, Object> input,
            String output,
            Boolean isError) {

        static PartResponse from(MessagePart part) {
            return switch (part) {
                case MessagePart.Text text -> new PartResponse(text.type(), text.text(),
                        null, null, null, null, null, null, null, null, null, null);
                case MessagePart.Audio audio -> new PartResponse(audio.type(), null, audio.mediaType(),
                        audio.sizeBytes(), audio.sha256(), audio.durationSeconds(), audio.transcript(),
                        null, null, null, null, null);
                case MessagePart.ToolCallPart toolCall -> new PartResponse(toolCall.type(), null,
                        null, null, null, null, null,
                        toolCall.call().toolCallId(), toolCall.call().toolName(), toolCall.call().input(), null, null);
                case MessagePart.ToolResultPart toolResult -> new PartResponse(toolResult.type(), null,
                        null, null, null, null, null,
                        toolResult.result().toolCallId(), toolResult.result().toolName(), null,
                        toolResult.result().output(), toolResult.result().isError());
            };
        }
    }
}
