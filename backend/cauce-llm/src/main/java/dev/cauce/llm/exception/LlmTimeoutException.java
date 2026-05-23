package dev.cauce.llm.exception;

/**
 * The request to the provider exceeded the configured timeout before a response arrived.
 */
public class LlmTimeoutException extends LlmProviderException {

    public LlmTimeoutException(String providerId, String modelName, String message) {
        super(providerId, modelName, message);
    }

    public LlmTimeoutException(String providerId, String modelName, String message, Throwable cause) {
        super(providerId, modelName, message, cause);
    }
}
