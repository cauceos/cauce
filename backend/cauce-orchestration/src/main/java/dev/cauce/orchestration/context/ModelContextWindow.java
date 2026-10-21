package dev.cauce.orchestration.context;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static table mapping a model name to its total context window in tokens. Pure, stateless
 * utility.
 *
 * <p>Hardcoded for now and limited to Anthropic models — the only provider with a functional
 * adapter (cauce-llm-anthropic). When a model is not in the table the lookup does not fail:
 * it returns a conservative {@link #DEFAULT_CONTEXT_WINDOW} and logs a one-time WARN, so a newly
 * released model degrades (history is trimmed to a safe window) instead of breaking the
 * invocation. The WARN is the operator's signal to register the model for optimal sizing. When a
 * second adapter lands, this table is a candidate for externalisation to configuration.
 */
public final class ModelContextWindow {

    private static final Logger log = LoggerFactory.getLogger(ModelContextWindow.class);

    /**
     * Conservative context window assumed for a model absent from {@link #KNOWN_MODELS}. Chosen
     * above {@code ContextBuilder.RESERVED_FOR_RESPONSE} so the effective window stays positive
     * and an unknown model still functions (trimming history) rather than failing every message.
     * Hardcoded by design — made configurable only if a real need arises.
     */
    static final int DEFAULT_CONTEXT_WINDOW = 16_384;

    // Context windows are the Claude API defaults, checked on 2026-10-21 against
    // platform.claude.com/docs/en/build-with-claude/context-windows and models/overview: Fable 5.1,
    // Opus 5.5, Sonnet 5.5 and Haiku 5.5 (current), and Fable 5, Opus 5, Sonnet 5, Opus 4.8/4.7/4.6
    // and Sonnet 4.6 (legacy) have a 1M window by default (no beta header); every other model,
    // Haiku 4.5 among them, has 200K. Sonnet 4.7 is not listed there and keeps the 200K it had.
    private static final Map<String, Integer> KNOWN_MODELS = Map.ofEntries(
            Map.entry("claude-fable-5-1", 1_000_000),
            Map.entry("claude-opus-5-5", 1_000_000),
            Map.entry("claude-sonnet-5-5", 1_000_000),
            Map.entry("claude-haiku-5-5", 1_000_000),
            Map.entry("claude-fable-5", 1_000_000),
            Map.entry("claude-opus-5", 1_000_000),
            Map.entry("claude-sonnet-5", 1_000_000),
            Map.entry("claude-opus-4-8", 1_000_000),
            Map.entry("claude-opus-4-7", 1_000_000),
            Map.entry("claude-opus-4-6", 1_000_000),
            Map.entry("claude-sonnet-4-6", 1_000_000),
            Map.entry("claude-sonnet-4-7", 200_000),
            Map.entry("claude-haiku-4-5", 200_000),
            Map.entry("claude-haiku-4-5-20251001", 200_000));

    /** Unknown model ids already warned about, so the fallback WARN fires once per id per JVM. */
    private static final Set<String> WARNED_UNKNOWN_MODELS = ConcurrentHashMap.newKeySet();

    private ModelContextWindow() {
    }

    /**
     * Returns the context window (in tokens) for {@code modelName}: the registered value when
     * known, otherwise a conservative {@link #DEFAULT_CONTEXT_WINDOW}. The fallback logs WARN
     * once per unknown model id (per JVM) so an operator can register it; it never throws, so an
     * unregistered model degrades rather than failing the invocation.
     */
    public static int contextWindowFor(String modelName) {
        Integer window = KNOWN_MODELS.get(modelName);
        if (window != null) {
            return window;
        }
        if (WARNED_UNKNOWN_MODELS.add(modelName)) {
            log.warn("Unknown model '{}', using conservative context window of {} tokens. "
                            + "Register the model in ModelContextWindow for optimal sizing.",
                    modelName, DEFAULT_CONTEXT_WINDOW);
        }
        return DEFAULT_CONTEXT_WINDOW;
    }
}
