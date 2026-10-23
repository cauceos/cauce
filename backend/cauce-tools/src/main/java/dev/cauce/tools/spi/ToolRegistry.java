package dev.cauce.tools.spi;

import dev.cauce.core.tool.ToolCatalog;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Registry of the tools available at runtime. Spring injects every {@link Tool} bean, keyed
 * by its {@link Tool#definition()} name. If no tool is registered the registry is simply
 * empty. Mirrors {@code dev.cauce.llm.spi.LlmProviderRegistry}.
 *
 * <p>Registration is global; per-agent tool scoping is deferred to a later unit. A duplicate
 * tool name is a configuration error and fails fast at startup (duplicate map key).
 */
@Component
public class ToolRegistry implements ToolCatalog {

    private final Map<String, Tool> toolsByName;

    public ToolRegistry(List<Tool> tools) {
        this.toolsByName = tools.stream()
                .collect(Collectors.toUnmodifiableMap(t -> t.definition().name(), Function.identity()));
    }

    /** The tool registered under {@code toolName}, if any. */
    public Optional<Tool> getTool(String toolName) {
        return Optional.ofNullable(toolsByName.get(toolName));
    }

    /**
     * The tool registered under {@code toolName}.
     *
     * @throws IllegalArgumentException if no tool is registered under that name
     */
    public Tool requireTool(String toolName) {
        Tool tool = toolsByName.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("No tool registered for name '" + toolName
                    + "'. Available: " + availableTools());
        }
        return tool;
    }

    /** The names of all registered tools. */
    /** {@inheritDoc} The registry is the catalog: a name is known exactly when a tool is registered. */
    @Override
    public boolean contains(String toolName) {
        return getTool(toolName).isPresent();
    }

    public Set<String> availableTools() {
        return toolsByName.keySet();
    }

    /** The registered tools that are lookups ({@link Tool#lookup()}): their past results may be elided. */
    public Set<String> lookupTools() {
        return toolsByName.values().stream().filter(Tool::lookup).map(tool -> tool.definition().name())
                .collect(Collectors.toUnmodifiableSet());
    }
}
