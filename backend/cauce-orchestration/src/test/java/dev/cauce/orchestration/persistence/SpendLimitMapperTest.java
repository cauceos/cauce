package dev.cauce.orchestration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.orchestration.usage.SpendLimit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpendLimitMapperTest {

    private final SpendLimitMapper mapper = new SpendLimitMapper();

    @Test
    void roundTrip_preservesEveryField() {
        SpendLimit limit = SpendLimit.create(UUID.randomUUID(), new BigDecimal("50.00"), UUID.randomUUID(),
                Instant.parse("2026-10-23T10:00:00Z"));

        assertThat(mapper.toDomain(mapper.toEntity(limit))).isEqualTo(limit);
    }
}
