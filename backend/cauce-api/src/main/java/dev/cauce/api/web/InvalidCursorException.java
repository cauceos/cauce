package dev.cauce.api.web;

/**
 * A pagination {@code cursor} query parameter that cannot be parsed. Mapped to
 * 400 {@code invalid_cursor} by {@link GlobalExceptionHandler}. The message stays generic —
 * the raw cursor value is never echoed back.
 */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String message) {
        super(message);
    }
}
