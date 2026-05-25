package dev.cauce.core.message;

/**
 * Thrown when an operation references a message that does not exist, is not visible to the
 * current tenant context, or does not belong to the expected conversation. The cases are
 * deliberately not distinguished, so the public API does not leak the existence of
 * entities outside the caller's scope.
 */
public class MessageNotFoundException extends RuntimeException {

    public MessageNotFoundException(String message) {
        super(message);
    }
}
