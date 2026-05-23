package dev.cauce.llm.exception;

/**
 * Base type for failures raised by an LLM provider adapter. Carries the {@code providerId}
 * and {@code modelName} of the failing invocation, and prefixes the message with that
 * context for debuggable logs.
 */
public class LlmProviderException extends RuntimeException {

    private final String providerId;
    private final String modelName;

    public LlmProviderException(String providerId, String modelName, String message) {
        this(providerId, modelName, message, null);
    }

    public LlmProviderException(String providerId, String modelName, String message, Throwable cause) {
        super("[provider=%s, model=%s] %s".formatted(providerId, modelName, message), cause);
        this.providerId = providerId;
        this.modelName = modelName;
    }

    public String providerId() {
        return providerId;
    }

    public String modelName() {
        return modelName;
    }
}
