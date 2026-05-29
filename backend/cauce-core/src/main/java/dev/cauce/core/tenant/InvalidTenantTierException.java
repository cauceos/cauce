package dev.cauce.core.tenant;

/**
 * Thrown when an operation requires a tenant of a specific tier but the referenced
 * tenant has a different one — e.g. creating a partner under a non-OPERATOR, a client
 * under a non-PARTNER, or an agent under a non-CLIENT.
 */
public class InvalidTenantTierException extends RuntimeException {

    public InvalidTenantTierException(String message) {
        super(message);
    }
}
