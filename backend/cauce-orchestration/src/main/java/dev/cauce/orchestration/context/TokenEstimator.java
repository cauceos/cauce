package dev.cauce.orchestration.context;

import java.util.Map;

/**
 * Conservative, provider-independent token estimator. Pure, stateless utility.
 *
 * <p>Uses a fixed characters-per-token ratio of 2.5, calibrated against billed input tokens (ADR
 * 0005, amendment of 2026-10-24): on the hospitality pack with Claude Sonnet 5.5, 3.5 fell short
 * by 22 % on average, and 2.5 overshoots by 5 % on average, every call within 10 %. Erring high
 * leaves headroom rather than overflowing a model's context window. This is a heuristic, not a
 * real tokenizer; a future version may delegate to a provider-specific tokenizer when accuracy
 * matters, and {@code cauce.orchestration.llm.input_tokens.estimate_ratio} says when to revisit it.
 */
public final class TokenEstimator {

    static final double CHARS_PER_TOKEN = 2.5;

    private TokenEstimator() {
    }

    /**
     * Estimates the token count of {@code text}. Returns 0 for {@code null} or empty input;
     * otherwise {@code ceil(length / 2.5)}.
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /**
     * Estimates the token count of {@code value} rendered as compact JSON, the shape a tool's input
     * schema takes on the wire: maps, iterables, strings (quoted), and anything else by its
     * {@code toString()}. The same ratio as {@link #estimate(String)}.
     */
    public static int estimateJson(Object value) {
        StringBuilder json = new StringBuilder();
        appendJson(json, value);
        return estimate(json.toString());
    }

    private static void appendJson(StringBuilder json, Object value) {
        if (value instanceof Map<?, ?> map) {
            json.append('{');
            String separator = "";
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                json.append(separator).append('"').append(entry.getKey()).append("\":");
                appendJson(json, entry.getValue());
                separator = ",";
            }
            json.append('}');
        } else if (value instanceof Iterable<?> items) {
            json.append('[');
            String separator = "";
            for (Object item : items) {
                json.append(separator);
                appendJson(json, item);
                separator = ",";
            }
            json.append(']');
        } else if (value instanceof CharSequence text) {
            json.append('"').append(text).append('"');
        } else {
            json.append(value);
        }
    }
}
