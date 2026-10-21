package dev.cauce.api.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.cauce.channels.config.ChannelConfigStatus;
import dev.cauce.channels.whatsapp.app.WhatsAppApp;
import dev.cauce.channels.whatsapp.app.WhatsAppAppRegistration;
import java.time.Instant;
import java.util.UUID;

/**
 * API representation of a WhatsApp app. Never carries the app secret. {@code verifyToken} is
 * present only in the registration response — the single time it exists in plaintext — and
 * {@code webhookPath} is what Meta's callback URL must end with.
 */
public record WhatsAppAppResponse(
        UUID id,
        UUID tenantId,
        String metaAppId,
        ChannelConfigStatus status,
        String webhookPath,
        @JsonInclude(JsonInclude.Include.NON_NULL) String verifyToken,
        Instant createdAt) {

    public static WhatsAppAppResponse from(WhatsAppApp app) {
        return new WhatsAppAppResponse(app.id(), app.tenantId(), app.metaAppId(), app.status(),
                app.webhookPath(), null, app.createdAt());
    }

    public static WhatsAppAppResponse from(WhatsAppAppRegistration registration) {
        WhatsAppApp app = registration.app();
        return new WhatsAppAppResponse(app.id(), app.tenantId(), app.metaAppId(), app.status(),
                app.webhookPath(), registration.verifyToken(), app.createdAt());
    }
}
