package dev.cauce.llm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmMessageTest {

    @Test
    void factories_setRoleAndContent() {
        assertThat(LlmMessage.user("hi").role()).isEqualTo(LlmRole.USER);
        assertThat(LlmMessage.assistant("hi").role()).isEqualTo(LlmRole.ASSISTANT);
        assertThat(LlmMessage.system("hi").role()).isEqualTo(LlmRole.SYSTEM);
        assertThat(LlmMessage.user("hello").content()).isEqualTo("hello");
    }

    @Test
    void textMessage_hasNoToolContent() {
        assertThat(LlmMessage.user("hi").toolContent()).isNull();
    }

    @Test
    void toolCall_carriesAssistantRoleAndTheCall() {
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of());

        LlmMessage message = LlmMessage.toolCall(call);

        assertThat(message.role()).isEqualTo(LlmRole.ASSISTANT);
        assertThat(message.toolContent()).isEqualTo(call);
    }

    @Test
    void toolResult_carriesUserRoleAndTheResult() {
        ToolResult result = ToolResult.success("call-1", "get_current_time", "2026-06-13T10:15:30Z");

        LlmMessage message = LlmMessage.toolResult(result);

        assertThat(message.role()).isEqualTo(LlmRole.USER);
        assertThat(message.toolContent()).isEqualTo(result);
    }

    @Test
    void rejectsNullRole() {
        assertThatThrownBy(() -> new LlmMessage(null, "hi"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullContent() {
        assertThatThrownBy(() -> LlmMessage.user(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toolCall_rejectsNull() {
        assertThatThrownBy(() -> LlmMessage.toolCall(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void valueEquality() {
        assertThat(LlmMessage.user("hi")).isEqualTo(LlmMessage.user("hi"));
        assertThat(LlmMessage.user("hi")).isNotEqualTo(LlmMessage.assistant("hi"));
    }
}
