package dev.cauce.llm.exception;

/**
 * The provider throttled the request (e.g. HTTP 429). Retry/backoff is the caller's
 * responsibility; the adapter does not retry in v1.0.
 */
public class LlmRateLimitException extends LlmProviderException {

    public LlmRateLimitException(String providerId, String modelName, String message) {
        super(providerId, modelName, message);
    }

    public LlmRateLimitException(String providerId, String modelName, String message, Throwable cause) {
        super(providerId, modelName, message, cause);
    }
}
