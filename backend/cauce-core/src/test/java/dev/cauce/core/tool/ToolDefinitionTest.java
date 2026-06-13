package dev.cauce.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolDefinitionTest {

    @Test
    void assignsFields() {
        ToolDefinition def = new ToolDefinition("get_current_time", "Returns the time",
                Map.of("type", "object"));

        assertThat(def.name()).isEqualTo("get_current_time");
        assertThat(def.description()).isEqualTo("Returns the time");
        assertThat(def.inputSchema()).containsEntry("type", "object");
    }

    @Test
    void nullDescription_defaultsToEmpty() {
        assertThat(new ToolDefinition("t", null, Map.of()).description()).isEmpty();
    }

    @Test
    void nullInputSchema_defaultsToEmptyMap() {
        assertThat(new ToolDefinition("t", "d", null).inputSchema()).isEmpty();
    }

    @Test
    void inputSchema_isDefensivelyCopied() {
        Map<String, Object> source = new HashMap<>();
        source.put("type", "object");
        ToolDefinition def = new ToolDefinition("t", "d", source);

        source.put("type", "mutated");

        assertThat(def.inputSchema()).containsEntry("type", "object");
        assertThatThrownBy(() -> def.inputSchema().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new ToolDefinition("  ", "d", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullName() {
        assertThatThrownBy(() -> new ToolDefinition(null, "d", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
