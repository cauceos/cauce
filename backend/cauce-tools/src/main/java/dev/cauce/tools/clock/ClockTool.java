package dev.cauce.tools.clock;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolDefinition;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.tools.spi.Tool;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Built-in tool that returns the current instant in ISO-8601 (UTC). Takes no arguments.
 *
 * <p>The {@link Clock} is injected so the tool is deterministic under test (a fixed clock),
 * and so a single source of time can be swapped centrally later. This is the trivial
 * reference tool that exercises the SPI end to end; it is not a catalogue.
 */
public final class ClockTool implements Tool {

    static final String NAME = "get_current_time";

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            NAME,
            "Returns the current date and time as an ISO-8601 instant in UTC. Takes no arguments.",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));

    private final Clock clock;

    public ClockTool(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Objects.requireNonNull(call, "call");
        String now = Instant.now(clock).toString();
        return ToolResult.success(call.toolCallId(), NAME, now);
    }
}
