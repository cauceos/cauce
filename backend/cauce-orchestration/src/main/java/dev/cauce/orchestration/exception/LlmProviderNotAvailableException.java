package dev.cauce.orchestration.exception;

/**
 * Thrown when no LLM provider is registered for an agent's {@code modelProvider}. Typically
 * the adapter is on the classpath but inactive because its API key is not configured, so it
 * never registered itself with the provider registry. The worker fails the invocation as
 * {@code AGENT_CONFIGURATION}: a setup to fix, never a provider outage — the provider was not
 * called, so nothing about it may count as down.
 */
public class LlmProviderNotAvailableException extends RuntimeException {

    public LlmProviderNotAvailableException(String message) {
        super(message);
    }
}
