package dev.cauce.core.tenant;

/**
 * Thrown when a tenant-scoped operation runs without a tenant context set.
 * This is fail-closed behavior: rather than querying without isolation, the
 * operation is rejected.
 */
public class MissingTenantContextException extends RuntimeException {

    public MissingTenantContextException(String message) {
        super(message);
    }
}
