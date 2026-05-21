package dev.cauce.core.agent;

/**
 * Thrown when an agent operation references a tenant that does not exist.
 */
public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException(String message) {
        super(message);
    }
}
