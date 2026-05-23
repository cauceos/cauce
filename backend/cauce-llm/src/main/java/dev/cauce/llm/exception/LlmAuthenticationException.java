package dev.cauce.llm.exception;

/**
 * The provider rejected the credential (e.g. HTTP 401): missing, invalid, or revoked key.
 */
public class LlmAuthenticationException extends LlmProviderException {

    public LlmAuthenticationException(String providerId, String modelName, String message) {
        super(providerId, modelName, message);
    }

    public LlmAuthenticationException(String providerId, String modelName, String message, Throwable cause) {
        super(providerId, modelName, message, cause);
    }
}
