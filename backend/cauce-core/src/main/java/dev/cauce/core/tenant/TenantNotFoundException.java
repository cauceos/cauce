package dev.cauce.core.tenant;

/**
 * Thrown when an operation references a tenant that does not exist or is not visible
 * to the current tenant context. The two cases are deliberately not distinguished, so
 * the public API does not leak the existence of tenants outside the caller's scope.
 */
public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException(String message) {
        super(message);
    }
}
