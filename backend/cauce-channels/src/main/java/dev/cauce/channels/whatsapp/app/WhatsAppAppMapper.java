package dev.cauce.channels.whatsapp.app;

import dev.cauce.core.secret.SecretCipher;
import org.springframework.stereotype.Component;

/**
 * Maps {@link WhatsAppApp} to and from its row. The app secret is sealed on the way to the
 * database and opened on the way back (ADR 0006, W3); unlike channel credentials there is no
 * legacy plaintext to tolerate — the table was born sealed.
 */
@Component
public final class WhatsAppAppMapper {

    private final SecretCipher cipher;

    public WhatsAppAppMapper(SecretCipher cipher) {
        this.cipher = cipher;
    }

    public WhatsAppAppEntity toEntity(WhatsAppApp app) {
        return new WhatsAppAppEntity(app.id(), app.tenantId(), app.metaAppId(), cipher.seal(app.appSecret()),
                app.verifyTokenHash(), app.status(), app.createdAt());
    }

    public WhatsAppApp toDomain(WhatsAppAppEntity entity) {
        return new WhatsAppApp(entity.getId(), entity.getTenantId(), entity.getMetaAppId(),
                cipher.open(entity.getAppSecret()), entity.getVerifyTokenHash(), entity.getStatus(),
                entity.getCreatedAt());
    }
}
