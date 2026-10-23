package dev.cauce.orchestration.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.orchestration.usage.SpendLimit.SpendState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpendLimitTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private final UUID clientId = UUID.randomUUID();
    private final UUID partnerId = UUID.randomUUID();

    @Test
    void create_mintsUuidV7_setByTheAncestor() {
        SpendLimit limit = SpendLimit.create(clientId, new BigDecimal("50.00"), partnerId, NOW);

        assertThat(limit.id().version()).isEqualTo(7);
        assertThat(limit.setByTenantId()).isEqualTo(partnerId);
        assertThat(limit.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void stateOf_isOkUnder80_warningFrom80_reachedFrom100() {
        SpendLimit limit = SpendLimit.create(clientId, new BigDecimal("50.00"), partnerId, NOW);

        assertThat(limit.stateOf(new BigDecimal("39.99"))).isEqualTo(SpendState.OK);
        assertThat(limit.stateOf(new BigDecimal("40.00"))).isEqualTo(SpendState.WARNING);
        assertThat(limit.stateOf(new BigDecimal("49.999999"))).isEqualTo(SpendState.WARNING);
        assertThat(limit.stateOf(new BigDecimal("50"))).isEqualTo(SpendState.REACHED);
        assertThat(limit.stateOf(new BigDecimal("75"))).isEqualTo(SpendState.REACHED);
    }

    @Test
    void aLimit_isPositive_inCents_andNeverTheTenantsOwn() {
        assertThatThrownBy(() -> SpendLimit.create(clientId, BigDecimal.ZERO, partnerId, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpendLimit.create(clientId, new BigDecimal("1.001"), partnerId, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpendLimit.create(clientId, BigDecimal.TEN, clientId, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changedTo_keepsTheId_andRecordsWhoAndWhen() {
        SpendLimit limit = SpendLimit.create(clientId, new BigDecimal("50.00"), partnerId, NOW);
        UUID operatorId = UUID.randomUUID();

        SpendLimit changed = limit.changedTo(new BigDecimal("80.00"), operatorId, NOW.plusSeconds(60));

        assertThat(changed.id()).isEqualTo(limit.id());
        assertThat(changed.monthlyLimitUsd()).isEqualByComparingTo("80");
        assertThat(changed.setByTenantId()).isEqualTo(operatorId);
        assertThat(changed).isNotEqualTo(limit);
    }
}
