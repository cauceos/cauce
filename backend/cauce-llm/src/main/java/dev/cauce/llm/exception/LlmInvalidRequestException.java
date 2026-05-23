package dev.cauce.llm.exception;

/**
 * The provider rejected the request as malformed or invalid (e.g. HTTP 400): unknown
 * model, bad parameters, or a payload the provider could not accept.
 */
public class LlmInvalidRequestException extends LlmProviderException {

    public LlmInvalidRequestException(String providerId, String modelName, String message) {
        super(providerId, modelName, message);
    }

    public LlmInvalidRequestException(String providerId, String modelName, String message, Throwable cause) {
        super(providerId, modelName, message, cause);
    }
}
