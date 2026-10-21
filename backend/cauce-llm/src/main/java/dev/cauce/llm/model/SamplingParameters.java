package dev.cauce.llm.model;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The sampling parameters of an invocation, for adapters that must tell "the model rejects a
 * parameter the agent configures" from any other invalid request. A sampling parameter is sent
 * only when the agent configures it; a provider's refusal that names one of those is a
 * configuration error of the agent ({@link dev.cauce.llm.exception.LlmParameterRejectedException}).
 */
public final class SamplingParameters {

    private SamplingParameters() {
    }

    /** The sampling parameters {@code invocation} sends, by their API names. */
    public static Set<String> sentBy(LlmInvocation invocation) {
        Set<String> sent = new LinkedHashSet<>();
        if (invocation.temperature() != null) {
            sent.add("temperature");
        }
        return sent;
    }

    /** The sent parameter a provider's refusal {@code detail} names, if any. */
    public static Optional<String> rejectedIn(String detail, Set<String> sent) {
        if (detail == null) {
            return Optional.empty();
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        return sent.stream().filter(lower::contains).findFirst();
    }
}
