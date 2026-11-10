package dev.cauce.api.webchat;

/** The request limits could not be checked (Redis unreachable): the request is refused, 503. */
public class RateLimitUnavailableException extends RuntimeException {

    public RateLimitUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
