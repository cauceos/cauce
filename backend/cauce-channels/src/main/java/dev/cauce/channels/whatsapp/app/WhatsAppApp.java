package dev.cauce.channels.whatsapp.app;

import dev.cauce.channels.config.ChannelConfigStatus;
import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A Meta app serving WhatsApp numbers (ADR 0006, W2): owned by a tenant, named by its webhook
 * URL ({@code /webhooks/whatsapp/{id}}), holding the secret Meta signs every webhook with and
 * the hash of the token Meta echoes when the webhook is subscribed. Numbers bind to it as
 * {@code whatsapp} channel configs, from the owning tenant or any tenant below it.
 *
 * <p>The domain holds {@code appSecret} in clear, because verifying an HMAC needs it; at rest it
 * is sealed by the mapper (W3). {@link #toString} never prints it.
 */
public record WhatsAppApp(UUID id,
                          UUID tenantId,
                          String metaAppId,
                          String appSecret,
                          String verifyTokenHash,
                          ChannelConfigStatus status,
                          Instant createdAt) {

    public WhatsAppApp {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        metaAppId = requireText(metaAppId, "metaAppId");
        appSecret = requireText(appSecret, "appSecret");
        verifyTokenHash = requireText(verifyTokenHash, "verifyTokenHash");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /** Registers a new ACTIVE app owned by {@code tenantId}, with a fresh UUIDv7. */
    public static WhatsAppApp create(UUID tenantId, String metaAppId, String appSecret, String verifyTokenHash) {
        return new WhatsAppApp(UuidGenerator.newV7(), tenantId, metaAppId, appSecret, verifyTokenHash,
                ChannelConfigStatus.ACTIVE, Instant.now());
    }

    /** The path Meta must be configured to call for this app. */
    public String webhookPath() {
        return "/webhooks/whatsapp/" + id;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    @Override
    public String toString() {
        // The secret and the token hash are never printed.
        return "WhatsAppApp[id=%s, tenantId=%s, metaAppId=%s, status=%s]".formatted(id, tenantId, metaAppId, status);
    }
}
