package dev.cauce.channels.whatsapp;

/**
 * A WhatsApp {@code phone_number_id} is already bound to a channel config on this instance
 * (ADR 0006, W2: one number is one door). Raised by the V31 unique index, whichever tenant holds
 * the existing binding — the caller learns only that the number is taken.
 */
public class PhoneNumberAlreadyBoundException extends RuntimeException {

    public PhoneNumberAlreadyBoundException(String message) {
        super(message);
    }
}
