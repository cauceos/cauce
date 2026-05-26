package dev.cauce.core.apikey;

/**
 * Thrown when an {@link ApiKey} cannot be loaded by id under the current tenant context.
 * The visibility filter does not distinguish "does not exist" from "exists but not
 * visible to you": both surface as this exception, to avoid leaking the existence of
 * out-of-scope rows.
 */
public class ApiKeyNotFoundException extends RuntimeException {

    public ApiKeyNotFoundException(String message) {
        super(message);
    }
}
