package dev.cauce.llm.model;

/**
 * Author role of an {@link LlmMessage} in the neutral, provider-independent model.
 * Adapters translate these to each provider's own role vocabulary.
 */
public enum LlmRole {
    USER,
    ASSISTANT,
    SYSTEM
}
