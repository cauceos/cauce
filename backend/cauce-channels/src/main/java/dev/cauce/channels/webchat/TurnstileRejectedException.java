package dev.cauce.channels.webchat;

/** A session was asked for with a Turnstile token that did not verify. */
public class TurnstileRejectedException extends RuntimeException {

    public TurnstileRejectedException(String message) {
        super(message);
    }
}
