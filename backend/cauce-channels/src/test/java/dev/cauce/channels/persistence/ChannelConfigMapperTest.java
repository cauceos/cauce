package dev.cauce.channels.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.config.ChannelConfigStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChannelConfigMapperTest {

    private final ChannelConfigMapper mapper = new ChannelConfigMapper();

    @Test
    void roundTrip_completeConfig_preservesEveryField() {
        ChannelConfig original = new ChannelConfig(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "telegram", "12345:bot-token", "hash-value",
                ChannelConfigStatus.DISABLED, Instant.parse("2026-07-04T10:00:00Z"));

        ChannelConfig roundTripped = mapper.toDomain(mapper.toEntity(original));

        assertThat(roundTripped).isEqualTo(original);
    }
}
