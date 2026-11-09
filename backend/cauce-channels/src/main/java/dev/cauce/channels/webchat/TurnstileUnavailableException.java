package dev.cauce.channels.webchat;

/** Turnstile could not be asked whether a token is valid: the session is not opened (fail closed). */
public class TurnstileUnavailableException extends RuntimeException {

    public TurnstileUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
