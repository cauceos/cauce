package dev.cauce.channels.webchat;

/**
 * A web-chat request without a session, or with one that is unknown, expired or of another channel,
 * all alike (ADR 0014, WC2): the widget opens a new session.
 */
public class InvalidSessionException extends RuntimeException {

    public InvalidSessionException(String message) {
        super(message);
    }
}
