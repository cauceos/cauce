package dev.cauce.orchestration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.orchestration.usage.LlmPrice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LlmPriceMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private final LlmPriceMapper mapper = new LlmPriceMapper();

    @Test
    void roundTrip_preservesEveryField() {
        LlmPrice price = LlmPrice.create(UUID.randomUUID(), "anthropic", "claude-sonnet-5-5", new BigDecimal("2.00"),
                new BigDecimal("10.00"), new BigDecimal("0.20"), new BigDecimal("2.50"), NOW);

        assertThat(mapper.toDomain(mapper.toEntity(price))).isEqualTo(price);
    }

    @Test
    void roundTrip_preservesATranscribersPricePerMinute() {
        LlmPrice price = LlmPrice.create(UUID.randomUUID(), "whisper", "Systran/faster-whisper-small",
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, new BigDecimal("0.006"), NOW);

        assertThat(mapper.toEntity(price).getAudioUsdPerMinute()).isEqualByComparingTo("0.006");
        assertThat(mapper.toDomain(mapper.toEntity(price))).isEqualTo(price);
    }

    @Test
    void roundTrip_preservesAbsentCachePrices() {
        LlmPrice price = LlmPrice.create(UUID.randomUUID(), "ollama", "qwen2.5:3b", BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, NOW);

        LlmPriceEntity entity = mapper.toEntity(price);

        assertThat(entity.getCacheReadUsdPerMtok()).isNull();
        assertThat(entity.getCacheWriteUsdPerMtok()).isNull();
        assertThat(mapper.toDomain(entity)).isEqualTo(price);
    }
}
