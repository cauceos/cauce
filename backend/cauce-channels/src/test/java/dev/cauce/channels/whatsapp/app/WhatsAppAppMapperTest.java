package dev.cauce.channels.whatsapp.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.channels.secret.AesGcmSecretCipher;
import dev.cauce.channels.secret.SecretEncryptionProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WhatsAppAppMapperTest {

    private final WhatsAppAppMapper mapper =
            new WhatsAppAppMapper(new AesGcmSecretCipher(SecretEncryptionProperties.DEV_ONLY_KEY));

    @Test
    void toEntity_sealsTheAppSecret_andTheRoundTripRestoresIt() {
        WhatsAppApp app = WhatsAppApp.create(UUID.randomUUID(), "1234567890", "app-secret-value", "hash");

        WhatsAppAppEntity entity = mapper.toEntity(app);

        assertThat(entity.getAppSecret()).startsWith("v1:").doesNotContain("app-secret-value");
        assertThat(mapper.toDomain(entity)).isEqualTo(app);
    }
}
