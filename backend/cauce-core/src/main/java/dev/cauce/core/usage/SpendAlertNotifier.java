package dev.cauce.core.usage;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * Port: tells a business's owner that it reached a share of its monthly spend limit (ADR 0010,
 * S6). Called once per tenant, month and threshold, inside the transaction that records the alert,
 * so the notice commits with it. Implemented by cauce-business as an owner notice; with no linked
 * owner the notice is recorded as not sent, never dropped.
 */
public interface SpendAlertNotifier {

    void thresholdReached(SpendAlert alert);

    /**
     * {@code tenantId} reached {@code thresholdPercent} (80 or 100) of its limit in {@code period},
     * a calendar month in UTC, having spent {@code spentUsd}.
     */
    record SpendAlert(UUID alertId, UUID tenantId, YearMonth period, int thresholdPercent, BigDecimal monthlyLimitUsd,
                      BigDecimal spentUsd) {

        public SpendAlert {
            Objects.requireNonNull(alertId, "alertId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(monthlyLimitUsd, "monthlyLimitUsd");
            Objects.requireNonNull(spentUsd, "spentUsd");
            if (thresholdPercent != 80 && thresholdPercent != 100) {
                throw new IllegalArgumentException("thresholdPercent must be 80 or 100");
            }
        }

        /** Whether the limit itself was reached: invocations fail until the month ends. */
        public boolean reached() {
            return thresholdPercent == 100;
        }
    }
}
