package dev.cauce.tools.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.tools.clock.ClockTool;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private final Tool clock =
            new ClockTool(Clock.fixed(Instant.parse("2026-06-13T10:15:30Z"), ZoneOffset.UTC));
    private final ToolRegistry registry = new ToolRegistry(List.of(clock));

    @Test
    void getTool_known_returnsTheTool() {
        assertThat(registry.getTool("get_current_time")).contains(clock);
    }

    @Test
    void getTool_unknown_returnsEmpty() {
        assertThat(registry.getTool("nope")).isEmpty();
    }

    @Test
    void requireTool_known_returnsTheTool() {
        assertThat(registry.requireTool("get_current_time")).isSameAs(clock);
    }

    @Test
    void requireTool_unknown_throwsWithAvailableNames() {
        assertThatThrownBy(() -> registry.requireTool("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("get_current_time");
    }

    @Test
    void availableTools_listsRegisteredNames() {
        assertThat(registry.availableTools()).containsExactly("get_current_time");
    }

    @Test
    void emptyRegistry_hasNoTools() {
        ToolRegistry empty = new ToolRegistry(List.of());

        assertThat(empty.availableTools()).isEmpty();
        assertThat(empty.getTool("get_current_time")).isEmpty();
    }
}
