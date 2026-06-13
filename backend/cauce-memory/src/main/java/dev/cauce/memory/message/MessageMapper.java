package dev.cauce.memory.message;

import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolContent;
import dev.cauce.core.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link Message} and its JPA
 * {@link MessageEntity}. No external mapping library is used.
 *
 * <p>Structured {@link ToolContent} is flattened to / rebuilt from the jsonb {@code tool_content}
 * map. The {@code role} discriminates the kind on the way back, so no extra type tag is stored;
 * Hibernate handles the map ⇄ jsonb serialization.
 */
@Component
public final class MessageMapper {

    private static final String KEY_TOOL_CALL_ID = "tool_call_id";
    private static final String KEY_TOOL_NAME = "tool_name";
    private static final String KEY_INPUT = "input";
    private static final String KEY_OUTPUT = "output";
    private static final String KEY_IS_ERROR = "is_error";

    public MessageEntity toEntity(Message message) {
        return new MessageEntity(
                message.id(),
                message.conversationId(),
                message.role(),
                message.content(),
                toToolContentMap(message.toolContent().orElse(null)),
                message.createdAt());
    }

    public Message toDomain(MessageEntity entity) {
        ToolContent toolContent = toToolContent(entity.getRole(), entity.getToolContent());
        if (toolContent == null) {
            return Message.rehydrate(
                    entity.getId(),
                    entity.getConversationId(),
                    entity.getRole(),
                    entity.getContent(),
                    entity.getCreatedAt());
        }
        return Message.rehydrate(
                entity.getId(),
                entity.getConversationId(),
                entity.getRole(),
                entity.getContent(),
                toolContent,
                entity.getCreatedAt());
    }

    private static Map<String, Object> toToolContentMap(ToolContent toolContent) {
        if (toolContent == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_TOOL_CALL_ID, toolContent.toolCallId());
        map.put(KEY_TOOL_NAME, toolContent.toolName());
        if (toolContent instanceof ToolCall call) {
            map.put(KEY_INPUT, call.input());
        } else if (toolContent instanceof ToolResult result) {
            map.put(KEY_OUTPUT, result.output());
            map.put(KEY_IS_ERROR, result.isError());
        }
        return map;
    }

    private static ToolContent toToolContent(MessageRole role, Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        String toolCallId = (String) map.get(KEY_TOOL_CALL_ID);
        String toolName = (String) map.get(KEY_TOOL_NAME);
        return switch (role) {
            case TOOL_CALL -> new ToolCall(toolCallId, toolName, asMap(map.get(KEY_INPUT)));
            case TOOL_RESULT -> new ToolResult(toolCallId, toolName,
                    (String) map.get(KEY_OUTPUT), Boolean.TRUE.equals(map.get(KEY_IS_ERROR)));
            // Text roles carry no tool content (enforced by the V14 guard CHECK).
            case USER, AGENT, SYSTEM -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }
}
