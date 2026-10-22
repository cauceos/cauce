package dev.cauce.core.business.handoff;

/**
 * Thrown when a handoff's customer message is not the person's own words: it does not appear in
 * any of their messages in the conversation. What the owner reads as the customer's message is
 * never the agent's paraphrase.
 */
public class UnquotedCustomerMessageException extends RuntimeException {

    public UnquotedCustomerMessageException(String message) {
        super(message);
    }
}
