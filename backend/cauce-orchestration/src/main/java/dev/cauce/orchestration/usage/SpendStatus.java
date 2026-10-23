package dev.cauce.orchestration.usage;

import dev.cauce.orchestration.usage.SpendLimit.SpendState;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a tenant's spend of the current month (calendar month in UTC) stands against its limit
 * (ADR 0010, S6). {@code spentUsd} is the cost of the priced calls; {@code unpricedCalls} are the
 * month's calls with no price in force when they were made.
 *
 * @param monthlyLimitUsd null when the tenant has no limit ({@link SpendState#NONE})
 */
public record SpendStatus(UUID tenantId, BigDecimal monthlyLimitUsd, YearMonth period, BigDecimal spentUsd,
                          long unpricedCalls, SpendState state) {

    public SpendStatus {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(spentUsd, "spentUsd");
        Objects.requireNonNull(state, "state");
    }

    public Optional<BigDecimal> limit() {
        return Optional.ofNullable(monthlyLimitUsd);
    }
}
