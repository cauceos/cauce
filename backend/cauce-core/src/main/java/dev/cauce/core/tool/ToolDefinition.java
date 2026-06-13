package dev.cauce.core.tool;

import java.util.Map;

/**
 * The neutral declaration of a tool the agent may call: a stable {@code name}, a
 * human/model-facing {@code description}, and a JSON-Schema-shaped {@code inputSchema}
 * describing the accepted arguments.
 *
 * <p>Provider-agnostic value object with no framework or provider dependencies. LLM
 * adapters (cauce-llm-*) translate this to each provider's wire format; the executable
 * contract that produces and consumes it lives in cauce-tools.
 */
public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {

    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
