package dev.cauce.orchestration.audit;

import dev.cauce.core.audit.AuditEvent;
import dev.cauce.orchestration.usage.LlmPrice;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The auditable vocabulary of usage, prices and spend limits (ADR 0010). Payloads carry ids and
 * non-sensitive metadata only: prices and amounts are the deployment's commercial figures, never
 * personal data.
 *
 * <p><b>Emitted</b> — each inside the transaction of the fact it audits: {@link #LLM_PRICE_ADDED}
 * (a price row, in the operator's chain).
 */
public final class UsageAuditEvents {

    public static final String LLM_PRICE_ADDED = "admin.llm_price.added";

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
        payload.put("valid_from", price.validFrom().toString());
        payload.put("actor_tenant_id", actorTenantId.toString());
        return new AuditEvent(price.tenantId(), LLM_PRICE_ADDED, payload);
    }
}
