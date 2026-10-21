package dev.cauce.core.business.handoff;

/** No handoff with that id is visible. */
public class HandoffNotFoundException extends RuntimeException {

    public HandoffNotFoundException(String message) {
        super(message);
    }
}
