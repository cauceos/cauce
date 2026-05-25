package dev.cauce.orchestration.exception;

/**
 * Thrown when the message that is supposed to trigger an agent response is not a USER
 * message. Only a user turn warrants generating an agent reply; an AGENT or SYSTEM message
 * as the trigger indicates a caller bug.
 */
public class InvalidTriggerMessageException extends RuntimeException {

    public InvalidTriggerMessageException(String message) {
        super(message);
    }
}
