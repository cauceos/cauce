package dev.cauce.orchestration.context;

import dev.cauce.orchestration.exception.UnknownModelException;
import java.util.Map;

/**
 * Static table mapping a model name to its total context window in tokens. Pure, stateless
 * utility.
 *
 * <p>Hardcoded for now and intentionally limited to Anthropic models — the only provider
 * with a functional adapter (cauce-llm-anthropic). When a second adapter lands, this table
 * is a candidate for externalisation to configuration; until then a hardcoded set with an
 * explicit {@link UnknownModelException} keeps behaviour predictable and avoids speculative
 * config surface.
 */
public final class ModelContextWindow {

    private static final Map<String, Integer> KNOWN_MODELS = Map.of(
            "claude-sonnet-4-7", 200_000,
            "claude-opus-4-7", 200_000,
            "claude-haiku-4-5", 200_000,
            "claude-sonnet-4-6", 200_000,
            "claude-opus-4-6", 200_000);

    private ModelContextWindow() {
    }

    /**
     * Returns the context window (in tokens) for {@code modelName}.
     *
     * @throws UnknownModelException if the model is not in the known-model table
     */
    public static int contextWindowFor(String modelName) {
        Integer window = KNOWN_MODELS.get(modelName);
        if (window == null) {
            throw new UnknownModelException(
                    "Unknown model '" + modelName + "'; no context window registered");
        }
        return window;
    }
}
