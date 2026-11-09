package dev.cauce.channels.webchat;

/**
 * A session was asked for without the explicit acceptance its channel requires (ADR 0014, WC5): the
 * data controller chose acceptance, and the person has not given it.
 */
public class ConsentRequiredException extends RuntimeException {

    public ConsentRequiredException(String message) {
        super(message);
    }
}
