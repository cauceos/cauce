package dev.cauce.governance.audit;

import dev.cauce.core.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background drainer of the audit outbox — a cousin of {@code PendingInvocationWorker}: a
 * scheduled poll discovers tenants with PENDING rows through the cross-tenant escape hatch,
 * then processes each tenant under its own {@code TenantContext} (set in a try / cleared in
 * a finally), one batch per tenant per tick, sequentially. Per-tenant partitioning is
 * guaranteed by the schema (per-tenant batches, per-tenant UNIQUE sequence), not by
 * parallelism — a parallel drain later needs no schema change.
 *
 * <p>A failure draining one tenant is logged and does not stop the others; the failed
 * tenant's rows stay PENDING and are retried on the next tick.
 */
@Component
@ConditionalOnProperty(prefix = "cauce.governance.audit.drainer", name = "enabled",
        matchIfMissing = true)
public class AuditOutboxDrainer {

    private static final Logger log = LoggerFactory.getLogger(AuditOutboxDrainer.class);

    private final AuditOutboxDrainService drainService;
    private final AuditDrainerProperties properties;

    public AuditOutboxDrainer(AuditOutboxDrainService drainService,
                              AuditDrainerProperties properties) {
        this.drainService = drainService;
        this.properties = properties;
    }

    /** One tick: drain a batch for every tenant that has PENDING outbox rows. */
    @Scheduled(fixedDelayString = "${cauce.governance.audit.drainer.interval-ms:5000}")
    public void drainAll() {
        List<UUID> tenants = drainService.pendingTenants();
        for (UUID tenantId : tenants) {
            TenantContext.setCurrentTenantId(tenantId);
            try {
                int drained = drainService.drainBatch(tenantId, properties.getBatchSize());
                if (drained > 0) {
                    log.debug("Drained {} audit outbox row(s) for tenant {}", drained, tenantId);
                }
            } catch (RuntimeException e) {
                log.error("Audit outbox drain failed for tenant {}; rows stay PENDING and will "
                        + "be retried", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
