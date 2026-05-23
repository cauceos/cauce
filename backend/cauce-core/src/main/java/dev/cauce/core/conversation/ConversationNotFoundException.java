package dev.cauce.core.conversation;

/**
 * Thrown when an operation references a conversation that does not exist or is not
 * visible to the current tenant context. The two cases are deliberately not
 * distinguished, so the public API does not leak the existence of entities outside
 * the caller's scope.
 */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(String message) {
        super(message);
    }
}
