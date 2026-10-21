package dev.cauce.channels.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.config.ChannelConfigStatus;
import dev.cauce.channels.secret.AesGcmSecretCipher;
import dev.cauce.channels.secret.SecretEncryptionProperties;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChannelConfigMapperTest {

    private final ChannelConfigMapper mapper = new ChannelConfigMapper(new AesGcmSecretCipher(SecretEncryptionProperties.DEV_ONLY_KEY));

    @Test
    void roundTrip_completeConfig_preservesEveryField() {
        ChannelConfig original = new ChannelConfig(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "telegram", "12345:bot-token", "hash-value",
                ChannelConfigStatus.DISABLED, Instant.parse("2026-07-04T10:00:00Z"));

        ChannelConfig roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void toEntity_sealsTheCredential_theRowNeverHoldsItInClear() {
        ChannelConfig config = ChannelConfig.create(UUID.randomUUID(), UUID.randomUUID(), "telegram",
                "12345:bot-token", "hash-value");

        ChannelConfigEntity entity = mapper.toEntity(config);

        assertThat(entity.getCredential()).startsWith("v1:").doesNotContain("bot-token");
    }

    @Test
    void toDomain_readsALegacyPlaintextCredentialAsIs() {
        ChannelConfig config = ChannelConfig.create(UUID.randomUUID(), UUID.randomUUID(), "telegram",
                "12345:bot-token", "hash-value");
        ChannelConfigEntity legacy = new ChannelConfigEntity(config.id(), config.tenantId(), config.agentId(),
                "telegram", "12345:bot-token", "hash-value", config.status(), config.createdAt());

        assertThat(mapper.toDomain(legacy).credential()).isEqualTo("12345:bot-token");
    }

    @Test
    void roundTrip_whatsAppNumber_preservesItsAppBinding() {
        ChannelConfig number = ChannelConfig.createWhatsAppNumber(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "1234567890", "EAAG-token");

        ChannelConfigEntity entity = mapper.toEntity(number);

        assertThat(entity.getWebhookSecretHash()).isNull();
        assertThat(entity.getCredential()).startsWith("v1:").doesNotContain("EAAG-token");
        assertThat(mapper.toDomain(entity)).isEqualTo(number);
    }
}
