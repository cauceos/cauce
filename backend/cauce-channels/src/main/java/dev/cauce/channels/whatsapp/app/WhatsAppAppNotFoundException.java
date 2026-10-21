package dev.cauce.channels.whatsapp.app;

/**
 * A WhatsApp app does not exist, is not ACTIVE where that is required, or is not visible to the
 * caller — indistinguishable on purpose.
 */
public class WhatsAppAppNotFoundException extends RuntimeException {

    public WhatsAppAppNotFoundException(String message) {
        super(message);
    }
}
