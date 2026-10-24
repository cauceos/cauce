package dev.cauce.core.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagePartsTest {

    private static final List<MessagePart> PARTS = List.of(
            new MessagePart.Text("Hola"),
            new MessagePart.ToolCallPart(new ToolCall("call-1", "search_catalog", Map.of("query", "bravas"))),
            new MessagePart.ToolResultPart(ToolResult.error("call-1", "search_catalog", "boom")));

    @Test
    void toJson_writesEachPartWithItsTypeAndTheV14ToolKeys_inOrder() {
        assertThat(MessageParts.toJson(PARTS)).containsExactly(
                Map.of("type", "text", "text", "Hola"),
                Map.of("type", "tool_call", "tool_call_id", "call-1", "tool_name", "search_catalog",
                        "input", Map.of("query", "bravas")),
                Map.of("type", "tool_result", "tool_call_id", "call-1", "tool_name", "search_catalog",
                        "output", "boom", "is_error", true));
    }

    @Test
    void fromJson_readsBackWhatToJsonWrote() {
        assertThat(MessageParts.fromJson(MessageParts.toJson(PARTS))).isEqualTo(PARTS);
    }

    @Test
    void fromJson_aResultWithoutIsError_isASuccess() {
        assertThat(MessageParts.fromJson(List.of(Map.of("type", "tool_result", "tool_call_id", "c",
                "tool_name", "t", "output", "ok"))))
                .containsExactly(new MessagePart.ToolResultPart(ToolResult.success("c", "t", "ok")));
    }

    @Test
    void fromJson_anUnknownType_fails() {
        assertThatThrownBy(() -> MessageParts.fromJson(List.of(Map.of("type", "audio"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audio");
    }
}
