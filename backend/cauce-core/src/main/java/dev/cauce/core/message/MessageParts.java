package dev.cauce.core.message;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one JSON form of a message's parts: what the {@code messages.parts} column stores and what
 * the content hash v2 hashes ({@code AuditContentHash#ofParts}), so the two can never disagree.
 * A list of objects, each with its {@code type}:
 *
 * <pre>
 * {"type": "text", "text": "..."}
 * {"type": "tool_call", "tool_call_id": "...", "tool_name": "...", "input": {...}}
 * {"type": "tool_result", "tool_call_id": "...", "tool_name": "...", "output": "...", "is_error": false}
 * </pre>
 *
 * The tool keys are the ones {@code tool_content} had (V14), so the backfill (V54) only adds the
 * {@code type}. Plain maps and lists: the core has no JSON library, and the persistence layer and
 * the canonical serializer both read these.
 */
public final class MessageParts {

    static final String KEY_TYPE = "type";
    static final String KEY_TEXT = "text";
    static final String KEY_TOOL_CALL_ID = "tool_call_id";
    static final String KEY_TOOL_NAME = "tool_name";
    static final String KEY_INPUT = "input";
    static final String KEY_OUTPUT = "output";
    static final String KEY_IS_ERROR = "is_error";

    private MessageParts() {
    }

    /** {@code parts} in their JSON form, in order. */
    public static List<Map<String, Object>> toJson(List<MessagePart> parts) {
        List<Map<String, Object>> json = new ArrayList<>(parts.size());
        for (MessagePart part : parts) {
            Map<String, Object> object = new LinkedHashMap<>();
            object.put(KEY_TYPE, part.type());
            switch (part) {
                case MessagePart.Text text -> object.put(KEY_TEXT, text.text());
                case MessagePart.ToolCallPart toolCall -> {
                    object.put(KEY_TOOL_CALL_ID, toolCall.call().toolCallId());
                    object.put(KEY_TOOL_NAME, toolCall.call().toolName());
                    object.put(KEY_INPUT, toolCall.call().input());
                }
                case MessagePart.ToolResultPart toolResult -> {
                    object.put(KEY_TOOL_CALL_ID, toolResult.result().toolCallId());
                    object.put(KEY_TOOL_NAME, toolResult.result().toolName());
                    object.put(KEY_OUTPUT, toolResult.result().output());
                    object.put(KEY_IS_ERROR, toolResult.result().isError());
                }
            }
            json.add(object);
        }
        return json;
    }

    /**
     * The parts {@code json} holds, in order.
     *
     * @throws IllegalArgumentException if an object has no known {@code type}
     */
    public static List<MessagePart> fromJson(List<Map<String, Object>> json) {
        List<MessagePart> parts = new ArrayList<>(json.size());
        for (Map<String, Object> object : json) {
            Object type = object.get(KEY_TYPE);
            parts.add(switch (String.valueOf(type)) {
                case MessagePart.Text.TYPE -> new MessagePart.Text((String) object.get(KEY_TEXT));
                case MessagePart.ToolCallPart.TYPE -> new MessagePart.ToolCallPart(new ToolCall(
                        (String) object.get(KEY_TOOL_CALL_ID), (String) object.get(KEY_TOOL_NAME),
                        asMap(object.get(KEY_INPUT))));
                case MessagePart.ToolResultPart.TYPE -> new MessagePart.ToolResultPart(new ToolResult(
                        (String) object.get(KEY_TOOL_CALL_ID), (String) object.get(KEY_TOOL_NAME),
                        (String) object.get(KEY_OUTPUT), Boolean.TRUE.equals(object.get(KEY_IS_ERROR))));
                default -> throw new IllegalArgumentException("Unknown message part type: " + type);
            });
        }
        return parts;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }
}
