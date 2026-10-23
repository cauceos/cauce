package dev.cauce.api.usage;

import dev.cauce.orchestration.usage.SpendStatus;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/**
 * A client's spend limit and where this month (calendar month in UTC, {@code yyyy-MM}) stands
 * against it. {@code monthly_limit_usd} is null and {@code state} {@code none} without a limit;
 * otherwise {@code state} is {@code ok}, {@code warning} (80 % or more: the owner was warned) or
 * {@code reached} (invocations fail with the fallback reply). {@code spent_usd} is the cost of the
 * priced calls; {@code unpriced_calls} are the month's calls with no price in force.
 */
public record SpendLimitResponse(UUID tenantId, BigDecimal monthlyLimitUsd, String period, BigDecimal spentUsd,
                                 long unpricedCalls, String state) {

    public static SpendLimitResponse from(SpendStatus status) {
        return new SpendLimitResponse(status.tenantId(), status.monthlyLimitUsd(), status.period().toString(),
                status.spentUsd(), status.unpricedCalls(), status.state().name().toLowerCase(Locale.ROOT));
    }
}
