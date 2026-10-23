package dev.cauce.orchestration.usage;

/**
 * Thrown when a tenant tries to set or remove its own spend limit (ADR 0010, S5): only a strict
 * ancestor does. The tenant can see its limit, so this is a refusal, not a 404.
 */
public class OwnSpendLimitException extends RuntimeException {

    public OwnSpendLimitException(String message) {
        super(message);
    }
}
