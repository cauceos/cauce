package dev.cauce.orchestration;

/**
 * Thrown when a {@link PendingInvocation} cannot be loaded by id under the current tenant
 * context. The visibility filter does not distinguish "does not exist" from "exists but
 * not visible to you": both surface as this exception, to avoid leaking the existence of
 * out-of-scope rows.
 */
public class PendingInvocationNotFoundException extends RuntimeException {

    public PendingInvocationNotFoundException(String message) {
        super(message);
    }
}
