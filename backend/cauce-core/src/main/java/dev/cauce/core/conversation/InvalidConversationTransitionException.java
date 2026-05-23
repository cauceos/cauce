package dev.cauce.core.conversation;

/**
 * Thrown when a conversation lifecycle transition is not allowed from the current
 * status (e.g. closing an already-CLOSED conversation, or any transition out of the
 * absorbing ARCHIVED state).
 */
public class InvalidConversationTransitionException extends RuntimeException {

    public InvalidConversationTransitionException(String message) {
        super(message);
    }
}
