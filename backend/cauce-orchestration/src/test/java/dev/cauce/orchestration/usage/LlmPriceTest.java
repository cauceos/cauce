package dev.cauce.orchestration.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LlmPriceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private final UUID operatorId = UUID.randomUUID();

    @Test
    void create_mintsUuidV7_inForceFromNow() {
        LlmPrice price = LlmPrice.create(operatorId, "anthropic", "claude-sonnet-5-5", new BigDecimal("2.00"),
                new BigDecimal("10.00"), new BigDecimal("0.20"), null, NOW);

        assertThat(price.id().version()).isEqualTo(7);
        assertThat(price.tenantId()).isEqualTo(operatorId);
        assertThat(price.validFrom()).isEqualTo(NOW);
        assertThat(price.cacheRead()).contains(new BigDecimal("0.20"));
        assertThat(price.cacheWrite()).isEmpty();
    }

    @Test
    void create_negativePrice_isRejected() {
        assertThatThrownBy(() -> LlmPrice.create(operatorId, "anthropic", "m", new BigDecimal("-1"),
                BigDecimal.ONE, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inputUsdPerMtok");
        assertThatThrownBy(() -> LlmPrice.create(operatorId, "anthropic", "m", BigDecimal.ONE, BigDecimal.ONE,
                null, new BigDecimal("-0.01"), NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cacheWriteUsdPerMtok");
    }

    @Test
    void create_blankModelOrMissingPrice_isRejected() {
        assertThatThrownBy(() -> LlmPrice.create(operatorId, "anthropic", " ", BigDecimal.ONE, BigDecimal.ONE,
                null, null, NOW)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("model");
        assertThatThrownBy(() -> LlmPrice.create(operatorId, "anthropic", "m", BigDecimal.ONE, null,
                null, null, NOW)).isInstanceOf(NullPointerException.class).hasMessageContaining("outputUsdPerMtok");
    }

    @Test
    void equalsAndHashCode_areByValue() {
        LlmPrice price = LlmPrice.create(operatorId, "anthropic", "m", BigDecimal.ONE, BigDecimal.TEN, null, null, NOW);
        LlmPrice same = new LlmPrice(price.id(), operatorId, "anthropic", "m", BigDecimal.ONE, BigDecimal.TEN,
                null, null, NOW);

        assertThat(same).isEqualTo(price).hasSameHashCodeAs(price);
    }
}
