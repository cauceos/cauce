package dev.cauce.channels.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChannelConfigTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    @Test
    void create_validArguments_startsActiveWithFreshId() {
        ChannelConfig config =
                ChannelConfig.create(TENANT_ID, AGENT_ID, "telegram", "token", "hash");

        assertThat(config.id()).isNotNull();
        assertThat(config.tenantId()).isEqualTo(TENANT_ID);
        assertThat(config.agentId()).isEqualTo(AGENT_ID);
        assertThat(config.channelType()).isEqualTo("telegram");
        assertThat(config.credential()).isEqualTo("token");
        assertThat(config.webhookSecretHash()).isEqualTo("hash");
        assertThat(config.status()).isEqualTo(ChannelConfigStatus.ACTIVE);
        assertThat(config.createdAt()).isNotNull();
    }

    @Test
    void create_mintsTimeOrderedIds() {
        ChannelConfig first = ChannelConfig.create(TENANT_ID, AGENT_ID, "telegram", "t", "h");
        ChannelConfig second = ChannelConfig.create(TENANT_ID, AGENT_ID, "telegram", "t", "h");

        assertThat(first.id()).isNotEqualTo(second.id());
    }

    @Test
    void create_nullOrBlankRequiredFields_areRejected() {
        assertThatThrownBy(() -> ChannelConfig.create(null, AGENT_ID, "telegram", "t", "h"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> ChannelConfig.create(TENANT_ID, null, "telegram", "t", "h"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("agentId");
        assertThatThrownBy(() -> ChannelConfig.create(TENANT_ID, AGENT_ID, "  ", "t", "h"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelType");
        assertThatThrownBy(() -> ChannelConfig.create(TENANT_ID, AGENT_ID, "telegram", "", "h"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential");
        assertThatThrownBy(() -> ChannelConfig.create(TENANT_ID, AGENT_ID, "telegram", "t", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("webhookSecretHash");
    }
}
