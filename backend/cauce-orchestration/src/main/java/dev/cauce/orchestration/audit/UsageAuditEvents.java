package dev.cauce.orchestration.audit;

import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.usage.SpendAlertNotifier.SpendAlert;
import dev.cauce.orchestration.usage.LlmPrice;
import dev.cauce.orchestration.usage.SpendLimit;
import dev.cauce.orchestration.usage.UsageReport;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The auditable vocabulary of usage, prices and spend limits (ADR 0010). Payloads carry ids and
 * non-sensitive metadata only: prices and amounts are the deployment's commercial figures, never
 * personal data.
 *
 * <p><b>Emitted</b> — each inside the transaction of the fact it audits: {@link #LLM_PRICE_ADDED}
 * (a price row, in the operator's chain), {@link #USAGE_READ} (a tenant read another tenant's usage,
 * S4, in the chain of the tenant read), {@link #SPEND_LIMIT_CHANGED} (a limit set, changed or
 * removed by an ancestor, S5, in the client's chain) and {@link #SPEND_THRESHOLD_REACHED} (the
 * client reached 80 or 100 % of its limit, once a month each, S6, in the client's chain).
 */
public final class UsageAuditEvents {

    public static final String LLM_PRICE_ADDED = "admin.llm_price.added";
    public static final String USAGE_READ = "admin.usage.read";
    public static final String SPEND_LIMIT_CHANGED = "admin.spend_limit.changed";
    public static final String SPEND_THRESHOLD_REACHED = "conduct.spend.threshold_reached";

    private UsageAuditEvents() {
    }

    /** A price was added, in the chain of the tenant that owns it (the operator). */
    public static AuditEvent llmPriceAdded(LlmPrice price, UUID actorTenantId) {
        Objects.requireNonNull(price, "price must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("price_id", price.id().toString());
        payload.put("tenant_id", price.tenantId().toString());
        payload.put("provider", price.provider());
        payload.put("model", price.model());
        payload.put("input_usd_per_mtok", price.inputUsdPerMtok().toPlainString());
        payload.put("output_usd_per_mtok", price.outputUsdPerMtok().toPlainString());
        price.cacheRead().ifPresent(p -> payload.put("cache_read_usd_per_mtok", p.toPlainString()));
        price.cacheWrite().ifPresent(p -> payload.put("cache_write_usd_per_mtok", p.toPlainString()));
        price.cacheWriteLong().ifPresent(p -> payload.put("cache_write_long_usd_per_mtok", p.toPlainString()));
        price.audioPerMinute().ifPresent(p -> payload.put("audio_usd_per_minute", p.toPlainString()));
        payload.put("valid_from", price.validFrom().toString());
        payload.put("actor_tenant_id", actorTenantId.toString());
        return new AuditEvent(price.tenantId(), LLM_PRICE_ADDED, payload);
    }

    /**
     * A tenant read the usage of another (ADR 0010, S4): what was read, never the figures, in the
     * chain of the tenant read, with the reader as actor.
     */
    public static AuditEvent usageRead(UsageReport report, UUID actorTenantId) {
        Objects.requireNonNull(report, "report must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        return new AuditEvent(report.tenantId(), USAGE_READ, Map.of(
                "tenant_id", report.tenantId().toString(),
                "from", report.from().toString(),
                "to", report.to().toString(),
                "granularity", report.granularity().name(),
                "group_by", report.grouping().name(),
                "time_zone", report.zone().getId(),
                "actor_tenant_id", actorTenantId.toString()));
    }

    /** A spend limit was set or changed by {@code limit.setByTenantId()}, in the client's chain. */
    public static AuditEvent spendLimitSet(SpendLimit limit, Optional<BigDecimal> previousUsd) {
        Objects.requireNonNull(limit, "limit must not be null");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", limit.tenantId().toString());
        payload.put("change", previousUsd.isPresent() ? "changed" : "set");
        payload.put("monthly_limit_usd", limit.monthlyLimitUsd().toPlainString());
        previousUsd.ifPresent(previous -> payload.put("previous_limit_usd", previous.toPlainString()));
        payload.put("actor_tenant_id", limit.setByTenantId().toString());
        return new AuditEvent(limit.tenantId(), SPEND_LIMIT_CHANGED, payload);
    }

    /** The spend limit of {@code tenantId} was removed by {@code actorTenantId}, in the client's chain. */
    public static AuditEvent spendLimitRemoved(UUID tenantId, BigDecimal previousUsd, UUID actorTenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(previousUsd, "previousUsd must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        return new AuditEvent(tenantId, SPEND_LIMIT_CHANGED, Map.of(
                "tenant_id", tenantId.toString(),
                "change", "removed",
                "previous_limit_usd", previousUsd.toPlainString(),
                "actor_tenant_id", actorTenantId.toString()));
    }

    /**
     * The client reached a threshold of its limit this month (S6). Runtime conduct with no acting
     * tenant: the check of an invocation found it.
     */
    public static AuditEvent spendThresholdReached(SpendAlert alert) {
        Objects.requireNonNull(alert, "alert must not be null");
        return new AuditEvent(alert.tenantId(), SPEND_THRESHOLD_REACHED, Map.of(
                "tenant_id", alert.tenantId().toString(),
                "alert_id", alert.alertId().toString(),
                "period", alert.period().toString(),
                "threshold_percent", alert.thresholdPercent(),
                "monthly_limit_usd", alert.monthlyLimitUsd().toPlainString(),
                "spent_usd", alert.spentUsd().toPlainString()));
    }
}
