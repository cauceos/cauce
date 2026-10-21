package dev.cauce.channels.persistence;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.core.secret.SecretCipher;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link ChannelConfig} and its JPA
 * {@link ChannelConfigEntity}. No external mapping library is used.
 *
 * <p>The provider credential is sealed on the way to the database and opened on the way back
 * (ADR 0006, W3): the domain holds it in clear, the row never does. A value stored before
 * encryption at rest is read as-is until {@code ChannelCredentialSealingRunner} seals it.
 */
@Component
public final class ChannelConfigMapper {

    private final SecretCipher cipher;

    public ChannelConfigMapper(SecretCipher cipher) {
        this.cipher = cipher;
    }

    public ChannelConfigEntity toEntity(ChannelConfig config) {
        return new ChannelConfigEntity(
                config.id(),
                config.tenantId(),
                config.agentId(),
                config.channelType(),
                cipher.seal(config.credential()),
                config.webhookSecretHash(),
                config.status(),
                config.createdAt(),
                config.whatsappAppId(),
                config.phoneNumberId());
    }

    public ChannelConfig toDomain(ChannelConfigEntity entity) {
        return new ChannelConfig(
                entity.getId(),
                entity.getTenantId(),
                entity.getAgentId(),
                entity.getChannelType(),
                cipher.isSealed(entity.getCredential())
                        ? cipher.open(entity.getCredential())
                        : entity.getCredential(), // legacy plaintext, sealed at startup
                entity.getWebhookSecretHash(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getWhatsappAppId(),
                entity.getPhoneNumberId());
    }
}
