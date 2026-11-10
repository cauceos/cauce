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
 * {"type": "audio", "media_type": "...", "provider_reference": "...", "size_bytes": 0, "sha256": "...",
 *  "duration_seconds": 0, "transcript": "..."}
 * {"type": "tool_call", "tool_call_id": "...", "tool_name": "...", "input": {...}}
 * {"type": "tool_result", "tool_call_id": "...", "tool_name": "...", "output": "...", "is_error": false,
 *  "product_cards": ["..."]}
 * {"type": "product_card", "ref": "..."}
 * {"type": "page_context", "path": "/...", "product_ref": "...", "cart": [{"ref": "...", "quantity": 1}],
 *  "greeting": "..."}
 * </pre>
 *
 * A tool result's {@code product_cards} is there only when it names some (ADR 0014, WC8), so a
 * result without them keeps the form, and the hash, it always had.
 *
 * An audio key whose value is not known yet is left out. The tool keys are the ones
 * {@code tool_content} had (V14), so the backfill (V54) only adds the {@code type}. Plain maps
 * and lists: the core has no JSON library, and the persistence layer and the canonical serializer
 * both read these.
 */
public final class MessageParts {

    static final String KEY_TYPE = "type";
    static final String KEY_TEXT = "text";
    static final String KEY_MEDIA_TYPE = "media_type";
    static final String KEY_PROVIDER_REFERENCE = "provider_reference";
    static final String KEY_SIZE_BYTES = "size_bytes";
    static final String KEY_SHA256 = "sha256";
    static final String KEY_DURATION_SECONDS = "duration_seconds";
    static final String KEY_TRANSCRIPT = "transcript";
    static final String KEY_TOOL_CALL_ID = "tool_call_id";
    static final String KEY_TOOL_NAME = "tool_name";
    static final String KEY_INPUT = "input";
    static final String KEY_OUTPUT = "output";
    static final String KEY_IS_ERROR = "is_error";
    static final String KEY_PRODUCT_CARDS = "product_cards";
    static final String KEY_REF = "ref";
    static final String KEY_PATH = "path";
    static final String KEY_PRODUCT_REF = "product_ref";
    static final String KEY_CART = "cart";
    static final String KEY_QUANTITY = "quantity";
    static final String KEY_GREETING = "greeting";

    private MessageParts() {
    }

    /** {@code parts} in their JSON form, in order. */
    public static List<Map<String, Object>> toJson(List<MessagePart> parts) {
        return toJson(parts, false);
    }

    /**
     * {@code parts} in the form the content hash v2 is computed over: the stored form, except that
     * an audio part keeps only what is known when it is received — its type, media type and
     * provider reference. What is learned later (the bytes' hash and size, the duration, the
     * transcript) would change the hash of a message already audited; it is committed by its own
     * audit record when it is learned (ADR 0012, V2).
     */
    public static List<Map<String, Object>> toHashedJson(List<MessagePart> parts) {
        return toJson(parts, true);
    }

    private static List<Map<String, Object>> toJson(List<MessagePart> parts, boolean asReceived) {
        List<Map<String, Object>> json = new ArrayList<>(parts.size());
        for (MessagePart part : parts) {
            Map<String, Object> object = new LinkedHashMap<>();
            object.put(KEY_TYPE, part.type());
            switch (part) {
                case MessagePart.Text text -> object.put(KEY_TEXT, text.text());
                case MessagePart.Audio audio -> {
                    object.put(KEY_MEDIA_TYPE, audio.mediaType());
                    object.put(KEY_PROVIDER_REFERENCE, audio.providerReference());
                    if (!asReceived) {
                        putIfKnown(object, KEY_SIZE_BYTES, audio.sizeBytes());
                        putIfKnown(object, KEY_SHA256, audio.sha256());
                        putIfKnown(object, KEY_DURATION_SECONDS, audio.durationSeconds());
                        putIfKnown(object, KEY_TRANSCRIPT, audio.transcript());
                    }
                }
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
                    if (!toolResult.result().productCards().isEmpty()) {
                        object.put(KEY_PRODUCT_CARDS, toolResult.result().productCards());
                    }
                }
                case MessagePart.ProductCard card -> object.put(KEY_REF, card.ref());
                case MessagePart.PageContext context -> {
                    object.put(KEY_PATH, context.path());
                    putIfKnown(object, KEY_PRODUCT_REF, context.productRef());
                    if (!context.cart().isEmpty()) {
                        object.put(KEY_CART, context.cart().stream().map(line -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put(KEY_REF, line.ref());
                            entry.put(KEY_QUANTITY, line.quantity());
                            return entry;
                        }).toList());
                    }
                    putIfKnown(object, KEY_GREETING, context.greeting());
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
                case MessagePart.Audio.TYPE -> new MessagePart.Audio((String) object.get(KEY_MEDIA_TYPE),
                        (String) object.get(KEY_PROVIDER_REFERENCE), asLong(object.get(KEY_SIZE_BYTES)),
                        (String) object.get(KEY_SHA256), asInteger(object.get(KEY_DURATION_SECONDS)),
                        (String) object.get(KEY_TRANSCRIPT));
                case MessagePart.ToolCallPart.TYPE -> new MessagePart.ToolCallPart(new ToolCall(
                        (String) object.get(KEY_TOOL_CALL_ID), (String) object.get(KEY_TOOL_NAME),
                        asMap(object.get(KEY_INPUT))));
                case MessagePart.ToolResultPart.TYPE -> new MessagePart.ToolResultPart(new ToolResult(
                        (String) object.get(KEY_TOOL_CALL_ID), (String) object.get(KEY_TOOL_NAME),
                        (String) object.get(KEY_OUTPUT), Boolean.TRUE.equals(object.get(KEY_IS_ERROR)),
                        asStrings(object.get(KEY_PRODUCT_CARDS))));
                case MessagePart.ProductCard.TYPE -> new MessagePart.ProductCard((String) object.get(KEY_REF));
                case MessagePart.PageContext.TYPE -> new MessagePart.PageContext((String) object.get(KEY_PATH),
                        (String) object.get(KEY_PRODUCT_REF), cartOf(object.get(KEY_CART)),
                        (String) object.get(KEY_GREETING));
                default -> throw new IllegalArgumentException("Unknown message part type: " + type);
            });
        }
        return parts;
    }

    private static List<MessagePart.PageContext.CartLine> cartOf(Object value) {
        if (value == null) {
            return List.of();
        }
        return ((List<?>) value).stream().map(entry -> asMap(entry)).map(line -> new MessagePart.PageContext.CartLine(
                (String) line.get(KEY_REF), asInteger(line.get(KEY_QUANTITY)))).toList();
    }

    private static List<String> asStrings(Object value) {
        if (value == null) {
            return List.of();
        }
        return ((List<?>) value).stream().map(String::valueOf).toList();
    }

    private static void putIfKnown(Map<String, Object> object, String key, Object value) {
        if (value != null) {
            object.put(key, value);
        }
    }

    private static Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer asInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }
}
