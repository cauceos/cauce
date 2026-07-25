package dev.cauce.governance.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the {@link AuditOutboxDrainer}.
 *
 * <p>Defaults match the drainer's {@code @Scheduled} fixed-delay string (also written there
 * as a fallback via {@code :default} syntax). Audit drainage tolerates more latency than the
 * invocation worker, hence the 5s poll (vs the worker's 1s).
 *
 * <pre>
 * cauce.governance.audit.drainer.enabled     = true   # drainer bean is created
 * cauce.governance.audit.drainer.interval-ms = 5000   # @Scheduled fixedDelay
 * cauce.governance.audit.drainer.batch-size  = 100    # outbox rows drained per tenant per tick
 * </pre>
 */
@ConfigurationProperties(prefix = "cauce.governance.audit.drainer")
public class AuditDrainerProperties {

    private boolean enabled = true;
    private long intervalMs = 5_000L;
    private int batchSize = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
