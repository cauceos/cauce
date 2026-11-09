package dev.cauce.core.usage;

/**
 * Thrown when an operation would leave a tenant without the spend limit something it runs requires
 * (ADR 0014, WC3): creating a web-chat channel for a tenant without a limit, or removing the limit of
 * a tenant with an active one.
 */
public class SpendLimitRequiredException extends RuntimeException {

    public SpendLimitRequiredException(String message) {
        super(message);
    }
}
