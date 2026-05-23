package dev.cauce.llm.model;

import java.util.Map;
import java.util.Objects;

/**
 * Declares a tool the model may call: a name, a human-readable description, and a JSON
 * Schema describing its input. Part of the SPI contract from v1.0, but not yet exercised
 * — invocations always carry an empty tool list until the Tool domain entity exists.
 */
public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {

    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
