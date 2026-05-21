package dev.cauce.core.agent;

/**
 * Thrown when an agent is created for a tenant whose tier is not allowed to own
 * agents (only CLIENT tenants may).
 */
public class InvalidTenantTierException extends RuntimeException {

    public InvalidTenantTierException(String message) {
        super(message);
    }
}
