package dev.cauce.channels.whatsapp.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.channels.config.ChannelConfigStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WhatsAppAppTest {

    @Test
    void create_isActive_withAUuidV7_andAWebhookPathNamingIt() {
        WhatsAppApp app = WhatsAppApp.create(UUID.randomUUID(), " 1234567890 ", "app-secret", "hash");

        assertThat(app.status()).isEqualTo(ChannelConfigStatus.ACTIVE);
        assertThat(app.id().version()).isEqualTo(7);
        assertThat(app.metaAppId()).isEqualTo("1234567890");
        assertThat(app.webhookPath()).isEqualTo("/webhooks/whatsapp/" + app.id());
    }

    @Test
    void create_requiresEveryField() {
        assertThatThrownBy(() -> WhatsAppApp.create(UUID.randomUUID(), " ", "s", "h"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WhatsAppApp.create(UUID.randomUUID(), "1", null, "h"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toString_neverPrintsTheSecretOrTheTokenHash() {
        WhatsAppApp app = WhatsAppApp.create(UUID.randomUUID(), "1", "super-secret", "token-hash");

        assertThat(app.toString()).doesNotContain("super-secret").doesNotContain("token-hash");
    }
}
