package dev.cauce.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCallTest {

    @Test
    void assignsFields() {
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of("tz", "UTC"));

        assertThat(call.toolCallId()).isEqualTo("call-1");
        assertThat(call.toolName()).isEqualTo("get_current_time");
        assertThat(call.input()).containsEntry("tz", "UTC");
    }

    @Test
    void isToolContent() {
        assertThat(new ToolCall("c", "t", Map.of())).isInstanceOf(ToolContent.class);
    }

    @Test
    void nullInput_defaultsToEmptyMap() {
        assertThat(new ToolCall("c", "t", null).input()).isEmpty();
    }

    @Test
    void input_isDefensivelyCopiedAndUnmodifiable() {
        Map<String, Object> source = new HashMap<>();
        source.put("a", 1);
        ToolCall call = new ToolCall("c", "t", source);

        source.put("a", 99);

        assertThat(call.input()).containsEntry("a", 1);
        assertThatThrownBy(() -> call.input().put("b", 2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankToolCallId() {
        assertThatThrownBy(() -> new ToolCall(" ", "t", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankToolName() {
        assertThatThrownBy(() -> new ToolCall("c", "", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
