package dev.cauce.tools.clock;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.business.profile.BusinessProfile;
import dev.cauce.core.business.profile.BusinessProfileSource;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolExecutionContext;
import dev.cauce.core.tool.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClockToolTest {

    private static final Instant FIXED = Instant.parse("2026-06-13T10:15:30Z");
    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext(UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final ClockTool tool = new ClockTool(Clock.fixed(FIXED, ZoneOffset.UTC));

    @Test
    void execute_returnsTheInjectedClockInstant() {
        ToolResult result = tool.execute(new ToolCall("call-1", ClockTool.NAME, Map.of()), CONTEXT);

        assertThat(result.toolCallId()).isEqualTo("call-1");
        assertThat(result.toolName()).isEqualTo("get_current_time");
        assertThat(result.output()).isEqualTo("2026-06-13T10:15:30Z");
        assertThat(result.isError()).isFalse();
    }

    @Test
    void execute_correlatesResultToTheCallId() {
        ToolResult result = tool.execute(new ToolCall("abc-123", ClockTool.NAME, Map.of()), CONTEXT);

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
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tool.execute(null, CONTEXT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void execute_whenTheBusinessHasAProfile_addsItsLocalDateTimeAndWeekday() {
        BusinessProfileSource profiles = tenant -> tenant.equals(CONTEXT.tenantId())
                ? Optional.of(BusinessProfile.create(tenant, "Bar", "Europe/Madrid", "es", List.of()))
                : Optional.empty();
        ClockTool local = new ClockTool(Clock.fixed(FIXED, ZoneOffset.UTC), () -> profiles);

        ToolResult result = local.execute(new ToolCall("call-1", ClockTool.NAME, Map.of()), CONTEXT);

        assertThat(result.output()).isEqualTo("{\"local_date\":\"2026-06-13\",\"local_time\":\"12:15:30\","
                + "\"time_zone\":\"Europe/Madrid\",\"utc\":\"2026-06-13T10:15:30Z\",\"weekday\":\"saturday\"}");
    }
}
