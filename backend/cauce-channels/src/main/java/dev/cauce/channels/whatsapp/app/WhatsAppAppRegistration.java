package dev.cauce.channels.whatsapp.app;

import java.util.Objects;

/**
 * The result of registering a WhatsApp app: the app, plus the verify token in plaintext — returned
 * exactly once, to be pasted into Meta's webhook configuration; only its hash is stored.
 */
public record WhatsAppAppRegistration(WhatsAppApp app, String verifyToken) {

    public WhatsAppAppRegistration {
        Objects.requireNonNull(app, "app must not be null");
        Objects.requireNonNull(verifyToken, "verifyToken must not be null");
    }
}
