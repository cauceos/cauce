package dev.cauce.tools.clock;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClockToolTest {

    private static final Instant FIXED = Instant.parse("2026-06-13T10:15:30Z");
    private final ClockTool tool = new ClockTool(Clock.fixed(FIXED, ZoneOffset.UTC));

    @Test
    void execute_returnsTheInjectedClockInstant() {
        ToolResult result = tool.execute(new ToolCall("call-1", ClockTool.NAME, Map.of()));

        assertThat(result.toolCallId()).isEqualTo("call-1");
        assertThat(result.toolName()).isEqualTo("get_current_time");
        assertThat(result.output()).isEqualTo("2026-06-13T10:15:30Z");
        assertThat(result.isError()).isFalse();
    }

    @Test
    void execute_correlatesResultToTheCallId() {
        ToolResult result = tool.execute(new ToolCall("abc-123", ClockTool.NAME, Map.of()));

        assertThat(result.toolCallId()).isEqualTo("abc-123");
    }

    @Test
    void definition_advertisesNameAndNoArgSchema() {
        ToolDefinition def = tool.definition();

        assertThat(def.name()).isEqualTo("get_current_time");
        assertThat(def.description()).isNotBlank();
        assertThat(def.inputSchema()).containsEntry("type", "object");
    }

    @Test
    void execute_rejectsNullCall() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tool.execute(null))
                .isInstanceOf(NullPointerException.class);
    }
}
