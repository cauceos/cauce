package dev.cauce.core.business.handoff;

/** A handoff category the business does not list; the message names the ones it does. */
public class InvalidHandoffCategoryException extends RuntimeException {

    public InvalidHandoffCategoryException(String message) {
        super(message);
    }
}
