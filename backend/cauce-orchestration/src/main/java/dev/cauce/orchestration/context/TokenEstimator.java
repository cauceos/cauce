package dev.cauce.orchestration.context;

/**
 * Conservative, provider-independent token estimator. Pure, stateless utility.
 *
 * <p>Uses a fixed characters-per-token ratio of 3.5 — deliberately low so the estimate
 * tends to overshoot, leaving headroom rather than overflowing a model's context window.
 * This is a heuristic, not a real tokenizer; a future version may delegate to a
 * provider-specific tokenizer when accuracy matters.
 */
public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN = 3.5;

    private TokenEstimator() {
    }

    /**
     * Estimates the token count of {@code text}. Returns 0 for {@code null} or empty input;
     * otherwise {@code ceil(length / 3.5)}.
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
