package dev.cauce.orchestration.usage;

import dev.cauce.core.UuidGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a CLIENT tenant may spend on models in a calendar month in UTC, in US$ (ADR 0010, S5), and
 * the ancestor that last set it. Checked before each invocation (S6).
 */
public record SpendLimit(UUID id, UUID tenantId, BigDecimal monthlyLimitUsd, UUID setByTenantId, Instant updatedAt) {

    /** The share of the limit at which the owner is warned. */
    public static final BigDecimal WARNING_RATIO = new BigDecimal("0.80");

    public SpendLimit {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(monthlyLimitUsd, "monthlyLimitUsd must not be null");
        Objects.requireNonNull(setByTenantId, "setByTenantId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (monthlyLimitUsd.signum() <= 0) {
            throw new IllegalArgumentException("monthlyLimitUsd must be positive");
        }
        if (monthlyLimitUsd.scale() > 2) {
            throw new IllegalArgumentException("monthlyLimitUsd has at most two decimals");
        }
        if (setByTenantId.equals(tenantId)) {
            throw new IllegalArgumentException("A tenant never sets its own spend limit");
        }
    }

    /** A new limit of {@code tenantId}, set by {@code setByTenantId} at {@code now} (database clock). */
    public static SpendLimit create(UUID tenantId, BigDecimal monthlyLimitUsd, UUID setByTenantId, Instant now) {
        return new SpendLimit(UuidGenerator.newV7(), tenantId, monthlyLimitUsd, setByTenantId, now);
    }

    /** This limit changed to {@code monthlyLimitUsd} by {@code setByTenantId} at {@code now}. */
    public SpendLimit changedTo(BigDecimal monthlyLimitUsd, UUID setByTenantId, Instant now) {
        return new SpendLimit(id, tenantId, monthlyLimitUsd, setByTenantId, now);
    }

    /** Where {@code spentUsd} stands against this limit. */
    public SpendState stateOf(BigDecimal spentUsd) {
        if (spentUsd.compareTo(monthlyLimitUsd) >= 0) {
            return SpendState.REACHED;
        }
        if (spentUsd.compareTo(monthlyLimitUsd.multiply(WARNING_RATIO)) >= 0) {
            return SpendState.WARNING;
        }
        return SpendState.OK;
    }

    /** Where a month's spend stands against the tenant's limit. */
    public enum SpendState {
        /** The tenant has no limit. */
        NONE,
        /** Under 80 % of the limit. */
        OK,
        /** At 80 % or more: the owner was warned; invocations run. */
        WARNING,
        /** At 100 % or more: invocations fail with the fallback reply until the month ends. */
        REACHED
    }
}
