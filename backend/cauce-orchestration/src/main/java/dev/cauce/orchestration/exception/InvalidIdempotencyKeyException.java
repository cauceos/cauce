package dev.cauce.orchestration.exception;

/**
 * Thrown when a caller supplies an idempotency key that cannot be stored or matched: blank,
 * or longer than the persisted column allows. A syntactically invalid key indicates a caller
 * bug — silently ignoring it would disable deduplication for a caller that asked for it.
 */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }
}
