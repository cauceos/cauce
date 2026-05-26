package dev.cauce.orchestration.worker;

import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.PendingInvocation;
import dev.cauce.orchestration.PendingInvocationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases hung claims back to PENDING (or marks them ABANDONED when the attempt budget
 * is gone). A PROCESSING row whose worker died — JVM crash, hard kill, lost DB
 * connectivity past commit — would otherwise sit forever; this scheduled job reaps any
 * row whose {@code claimed_at} is older than
 * {@code cauce.orchestration.worker.reaper.timeout-ms}.
 *
 * <p>Per-row processing sets {@code TenantContext} to the row's owning tenant before
 * invoking the tenant-scoped service methods, mirroring the worker. The discovery query
 * is cross-tenant ({@code @NoTenantContext} downstream).
 *
 * <p>Disabled by {@code cauce.orchestration.worker.reaper.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "cauce.orchestration.worker.reaper",
        name = "enabled", matchIfMissing = true)
public class PendingInvocationReaper {

    private static final Logger log = LoggerFactory.getLogger(PendingInvocationReaper.class);
    private static final String REAP_ERROR = "reaped (claim timeout)";

    private final PendingInvocationService pendingInvocationService;
    private final PendingInvocationWorkerProperties properties;

    public PendingInvocationReaper(PendingInvocationService pendingInvocationService,
                                   PendingInvocationWorkerProperties properties) {
        this.pendingInvocationService = pendingInvocationService;
        this.properties = properties;
    }

    /**
     * Single reaping tick. Public so integration tests can invoke deterministically.
     */
    @Scheduled(fixedDelayString = "${cauce.orchestration.worker.reaper.interval-ms:300000}")
    public void reapOrphanedInvocations() {
        Instant threshold = Instant.now().minus(Duration.ofMillis(properties.getReaper().getTimeoutMs()));
        List<PendingInvocation> orphans;
        try {
            orphans = pendingInvocationService.findOrphanedSince(threshold);
        } catch (RuntimeException e) {
            log.warn("Reaper failed to scan for orphaned invocations: {}", e.toString(), e);
            return;
        }
        if (orphans.isEmpty()) {
            return;
        }
        log.info("Reaper found {} orphaned invocation(s) older than {}", orphans.size(), threshold);
        long base = properties.getRetryBaseIntervalSeconds();
        for (PendingInvocation invocation : orphans) {
            TenantContext.setCurrentTenantId(invocation.tenantId());
            try {
                if (invocation.attemptCount() >= invocation.maxAttempts()) {
                    pendingInvocationService.markAbandoned(invocation.id(), REAP_ERROR);
                    log.warn("Reaper abandoned invocation {} after exhausting {} attempt(s)",
                            invocation.id(), invocation.maxAttempts());
                } else {
                    pendingInvocationService.releaseForRetry(invocation.id(), REAP_ERROR, base);
                    log.info("Reaper released invocation {} for retry (attempt {}/{})",
                            invocation.id(), invocation.attemptCount(), invocation.maxAttempts());
                }
            } catch (RuntimeException e) {
                log.error("Reaper failed to recover invocation {}: {}",
                        invocation.id(), e.toString(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
