package dev.cauce.memory.message;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageMapperTest {

    private final MessageMapper mapper = new MessageMapper();

    @Test
    void roundTrip_domainToEntityToDomain_preservesAllFields() {
        Message original = Message.from(UUID.randomUUID(), MessageRole.USER,
                "  Hello, with whitespace preserved\n");

        Message roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped.id()).isEqualTo(original.id());
        assertThat(roundTripped.conversationId()).isEqualTo(original.conversationId());
        assertThat(roundTripped.role()).isEqualTo(MessageRole.USER);
        assertThat(roundTripped.content()).isEqualTo(original.content());
        assertThat(roundTripped.createdAt()).isEqualTo(original.createdAt());
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void toEntity_copiesAllFields() {
        Message message = Message.from(UUID.randomUUID(), MessageRole.AGENT, "Reply");

        MessageEntity entity = mapper.toEntity(message);

        assertThat(entity.getId()).isEqualTo(message.id());
        assertThat(entity.getConversationId()).isEqualTo(message.conversationId());
        assertThat(entity.getRole()).isEqualTo(MessageRole.AGENT);
        assertThat(entity.getContent()).isEqualTo("Reply");
        assertThat(entity.getCreatedAt()).isEqualTo(message.createdAt());
    }

    @Test
    void toEntity_textMessage_leavesToolContentNull() {
        MessageEntity entity = mapper.toEntity(
                Message.from(UUID.randomUUID(), MessageRole.USER, "hi"));

        assertThat(entity.getToolContent()).isNull();
    }

    @Test
    void roundTrip_toolCallMessage_preservesStructuredContentAndCorrelationId() {
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of("tz", "UTC"));
        Message original = Message.toolCall(UUID.randomUUID(), call);

        Message roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped.role()).isEqualTo(MessageRole.TOOL_CALL);
        assertThat(roundTripped.toolContent()).contains(call);
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void roundTrip_toolResultMessage_preservesOutputErrorFlagAndCorrelationId() {
        ToolResult result = ToolResult.error("call-1", "get_current_time", "boom");
        Message original = Message.toolResult(UUID.randomUUID(), result);

        Message roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped.role()).isEqualTo(MessageRole.TOOL_RESULT);
        assertThat(roundTripped.toolContent()).contains(result);
    }

    @Test
    void toEntity_toolCall_writesCorrelationIdAndNameIntoJsonbMap() {
        ToolCall call = new ToolCall("call-9", "get_current_time", Map.of());

        MessageEntity entity = mapper.toEntity(Message.toolCall(UUID.randomUUID(), call));

        assertThat(entity.getToolContent())
                .containsEntry("tool_call_id", "call-9")
                .containsEntry("tool_name", "get_current_time")
                .containsKey("input");
    }
}
