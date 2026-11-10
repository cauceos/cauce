package dev.cauce.channels.webchat;

/** A web chat's message or read was malformed: blank or too long, or with a cursor that names nothing. */
public class InvalidWebMessageException extends RuntimeException {

    public InvalidWebMessageException(String message) {
        super(message);
    }
}
