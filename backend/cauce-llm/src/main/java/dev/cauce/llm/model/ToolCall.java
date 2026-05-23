package dev.cauce.llm.model;

import java.util.Map;
import java.util.Objects;

/**
 * A tool invocation requested by the model in its response: an id, the tool name, and the
 * arguments. Part of the SPI contract from v1.0, but not yet exercised — responses always
 * carry an empty tool-call list until the Tool domain entity exists.
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
