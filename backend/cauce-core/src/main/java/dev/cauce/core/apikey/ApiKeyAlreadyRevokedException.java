package dev.cauce.core.apikey;

/**
 * Thrown by {@link ApiKey#revoke()} when the key has already been revoked. Revocation
 * is a one-way transition: a revoked key cannot be reactivated.
 */
public class ApiKeyAlreadyRevokedException extends RuntimeException {

    public ApiKeyAlreadyRevokedException(String message) {
        super(message);
    }
}
