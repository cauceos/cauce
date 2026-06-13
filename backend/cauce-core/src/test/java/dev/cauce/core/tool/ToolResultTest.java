package dev.cauce.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ToolResultTest {

    @Test
    void success_buildsNonErrorResult() {
        ToolResult result = ToolResult.success("call-1", "get_current_time", "2026-06-13T10:15:30Z");

        assertThat(result.toolCallId()).isEqualTo("call-1");
        assertThat(result.toolName()).isEqualTo("get_current_time");
        assertThat(result.output()).isEqualTo("2026-06-13T10:15:30Z");
        assertThat(result.isError()).isFalse();
    }

    @Test
    void error_buildsErrorResult() {
        ToolResult result = ToolResult.error("call-1", "get_current_time", "boom");

        assertThat(result.output()).isEqualTo("boom");
        assertThat(result.isError()).isTrue();
    }

    @Test
    void isToolContent() {
        assertThat(ToolResult.success("c", "t", "o")).isInstanceOf(ToolContent.class);
    }

    @Test
    void allowsEmptyOutput() {
        assertThat(ToolResult.success("c", "t", "").output()).isEmpty();
    }

    @Test
    void rejectsNullOutput() {
        assertThatThrownBy(() -> new ToolResult("c", "t", null, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankToolCallId() {
        assertThatThrownBy(() -> ToolResult.success(" ", "t", "o"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankToolName() {
        assertThatThrownBy(() -> ToolResult.success("c", " ", "o"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
