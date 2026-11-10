package dev.cauce.channels.webchat;

/**
 * The person's thread reached its bound (ADR 0014, WC3): the widget offers to start again, with
 * {@code new_thread}, and the session goes on.
 */
public class ThreadFullException extends RuntimeException {

    public ThreadFullException(String message) {
        super(message);
    }
}
