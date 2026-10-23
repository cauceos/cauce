package dev.cauce.core.agent;

/**
 * How long an agent's prompt prefix is expected to be reused (ADR 0010, S9): a neutral hint the LLM
 * SPI carries, which each provider maps to what it offers or ignores. {@link #SHORT} is the default,
 * minutes; {@link #LONG} asks the provider to keep the prefix about an hour, when a conversation's
 * messages are minutes apart and a write costs more but is reused. It changes how a call is billed,
 * never what the model is sent.
 */
public enum PromptCacheRetention {
    SHORT,
    LONG
}
