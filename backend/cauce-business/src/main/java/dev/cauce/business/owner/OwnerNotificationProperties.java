package dev.cauce.business.owner;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The owner-notification dispatcher (ADR 0007, B9):
 *
 * <pre>
 * cauce.business.notifications.enabled = true         # the scheduled dispatcher runs
 * cauce.business.notifications.interval-ms = 10000    # @Scheduled fixed delay
 * cauce.business.notifications.batch-size = 20        # notices claimed per tick
 * cauce.business.notifications.lease-seconds = 60     # a claim's lease, and a failed send's backoff
 * cauce.business.notifications.max-attempts = 5       # sends before a failing notice is FAILED
 * </pre>
 */
@ConfigurationProperties("cauce.business.notifications")
public class OwnerNotificationProperties {

    private boolean enabled = true;
    private long intervalMs = 10_000;
    private int batchSize = 20;
    private int leaseSeconds = 60;
    private int maxAttempts = 5;

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

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
