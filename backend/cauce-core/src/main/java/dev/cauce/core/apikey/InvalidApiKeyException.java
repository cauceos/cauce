package dev.cauce.core.apikey;

/**
 * Thrown when an API key fails validation in a domain context — bad format, unknown
 * prefix, or hash mismatch. The Spring Security filter catches this and translates it
 * to an HTTP 401 response; the domain itself does not know about HTTP.
 */
public class InvalidApiKeyException extends RuntimeException {

    public InvalidApiKeyException(String message) {
        super(message);
    }
}
