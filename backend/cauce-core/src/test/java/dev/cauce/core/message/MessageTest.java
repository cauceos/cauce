package dev.cauce.core.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageTest {

    private static final UUID CONVERSATION = UUID.randomUUID();

    @Test
    void from_generatesUuidV7() {
        assertThat(newMessage().id().version()).isEqualTo(7);
    }

    @Test
    void from_assignsFieldsAndTimestamp() {
        Message message = Message.from(CONVERSATION, MessageRole.USER, "Hello");

        assertThat(message.conversationId()).isEqualTo(CONVERSATION);
        assertThat(message.role()).isEqualTo(MessageRole.USER);
        assertThat(message.content()).isEqualTo("Hello");
        assertThat(message.createdAt()).isNotNull();
    }

    @Test
    void from_preservesContentVerbatim_withoutTrimming() {
        Message message = Message.from(CONVERSATION, MessageRole.USER, "  spaced\n");

        assertThat(message.content()).isEqualTo("  spaced\n");
    }

    @Test
    void from_rejectsNullConversationId() {
        assertThatThrownBy(() -> Message.from(null, MessageRole.USER, "Hi"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void from_rejectsNullRole() {
        assertThatThrownBy(() -> Message.from(CONVERSATION, null, "Hi"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void from_rejectsNullContent() {
        assertThatThrownBy(() -> Message.from(CONVERSATION, MessageRole.USER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void from_rejectsEmptyContent() {
        assertThatThrownBy(() -> Message.from(CONVERSATION, MessageRole.USER, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void from_rejectsBlankContent() {
        assertThatThrownBy(() -> Message.from(CONVERSATION, MessageRole.USER, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toString_truncatesLongContent_andDoesNotExposeFullBody() {
        String longContent = "x".repeat(200);
        Message message = Message.from(CONVERSATION, MessageRole.AGENT, longContent);

        String text = message.toString();

        assertThat(text).contains("x".repeat(50) + "...");
        assertThat(text).doesNotContain("x".repeat(51));
    }

    @Test
    void toString_doesNotTruncateShortContent() {
        Message message = Message.from(CONVERSATION, MessageRole.AGENT, "short");

        assertThat(message.toString()).contains("content=short").doesNotContain("...");
    }

    @Test
    void equalsAndHashCode_areBasedOnId() {
        Message a = newMessage();
        Message sameId = Message.rehydrate(a.id(), UUID.randomUUID(), MessageRole.SYSTEM,
                "other", a.createdAt());
        Message different = newMessage();

        assertThat(a).isEqualTo(sameId);
        assertThat(a).hasSameHashCodeAs(sameId);
        assertThat(a).isNotEqualTo(different);
    }

    @Test
    void from_textMessage_hasNoToolContent() {
        assertThat(newMessage().toolContent()).isEmpty();
    }

    @Test
    void from_rejectsToolRoleWithoutToolContent() {
        assertThatThrownBy(() -> Message.from(CONVERSATION, MessageRole.TOOL_CALL, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolCall_generatesUuidV7AndAssignsFields() {
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of("tz", "UTC"));

        Message message = Message.toolCall(CONVERSATION, call);

        assertThat(message.id().version()).isEqualTo(7);
        assertThat(message.conversationId()).isEqualTo(CONVERSATION);
        assertThat(message.role()).isEqualTo(MessageRole.TOOL_CALL);
        assertThat(message.content()).isEqualTo("get_current_time"); // rendered from the call
        assertThat(message.toolContent()).contains(call);
        assertThat(message.createdAt()).isNotNull();
    }

    @Test
    void toolResult_assignsFieldsAndRendersOutputAsContent() {
        ToolResult result = ToolResult.success("call-1", "get_current_time", "2026-06-13T10:15:30Z");

        Message message = Message.toolResult(CONVERSATION, result);

        assertThat(message.role()).isEqualTo(MessageRole.TOOL_RESULT);
        assertThat(message.content()).isEqualTo("2026-06-13T10:15:30Z"); // rendered from the output
        assertThat(message.toolContent()).contains(result);
    }

    @Test
    void toolResult_blankOutput_rendersPlaceholderContent() {
        ToolResult result = ToolResult.success("call-1", "noop", "");

        Message message = Message.toolResult(CONVERSATION, result);

        assertThat(message.content()).isEqualTo("[empty result]");
        assertThat(message.toolContent()).contains(result);
    }

    @Test
    void toolCall_rejectsNullCall() {
        assertThatThrownBy(() -> Message.toolCall(CONVERSATION, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toolResult_rejectsNullResult() {
        assertThatThrownBy(() -> Message.toolResult(CONVERSATION, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rehydrate_toolCallMessage_preservesToolContent() {
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of());
        Message original = Message.toolCall(CONVERSATION, call);

        Message rehydrated = Message.rehydrate(original.id(), CONVERSATION, MessageRole.TOOL_CALL,
                original.content(), call, original.createdAt());

        assertThat(rehydrated.toolContent()).contains(call);
        assertThat(rehydrated.role()).isEqualTo(MessageRole.TOOL_CALL);
    }

    @Test
    void rehydrate_rejectsRoleAndToolContentMismatch() {
        ToolResult result = ToolResult.success("call-1", "get_current_time", "now");

        assertThatThrownBy(() -> Message.rehydrate(UUID.randomUUID(), CONVERSATION,
                MessageRole.TOOL_CALL, "get_current_time", result, java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rehydrate_rejectsTextRoleCarryingToolContent() {
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of());

        assertThatThrownBy(() -> Message.rehydrate(UUID.randomUUID(), CONVERSATION,
                MessageRole.USER, "hi", call, java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Message newMessage() {
        return Message.from(CONVERSATION, MessageRole.USER, "Hello");
    }
}
