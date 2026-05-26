package dev.cauce.orchestration.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the asynchronous {@link PendingInvocationWorker} and the
 * {@link PendingInvocationReaper}.
 *
 * <p>Defaults match the values used by the worker's {@code @Scheduled} fixed-delay strings
 * (also written here as fallbacks via {@code :default} syntax). The polling interval and
 * reaper interval are read directly by Spring from properties; the rest is consumed in code
 * via this bean.
 *
 * <pre>
 * cauce.orchestration.worker.enabled                  = true     # worker bean is created
 * cauce.orchestration.worker.poll-interval-ms         = 1000     # @Scheduled fixedDelay
 * cauce.orchestration.worker.batch-size               = 5        # rows claimed per poll
 * cauce.orchestration.worker.thread-pool-size         = 10       # executor core/max size
 * cauce.orchestration.worker.retry-base-interval-seconds = 30    # backoff seed
 * cauce.orchestration.worker.reaper.enabled           = true     # reaper bean is created
 * cauce.orchestration.worker.reaper.interval-ms       = 300000   # @Scheduled fixedDelay (5min)
 * cauce.orchestration.worker.reaper.timeout-ms        = 600000   # claim age before reaping (10min)
 * </pre>
 */
@ConfigurationProperties(prefix = "cauce.orchestration.worker")
public class PendingInvocationWorkerProperties {

    private boolean enabled = true;
    private long pollIntervalMs = 1_000L;
    private int batchSize = 5;
    private int threadPoolSize = 10;
    private long retryBaseIntervalSeconds = 30L;
    private Reaper reaper = new Reaper();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public void setThreadPoolSize(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
    }

    public long getRetryBaseIntervalSeconds() {
        return retryBaseIntervalSeconds;
    }

    public void setRetryBaseIntervalSeconds(long retryBaseIntervalSeconds) {
        this.retryBaseIntervalSeconds = retryBaseIntervalSeconds;
    }

    public Reaper getReaper() {
        return reaper;
    }

    public void setReaper(Reaper reaper) {
        this.reaper = reaper;
    }

    /** Nested config for the reaper that releases hung PROCESSING claims. */
    public static class Reaper {

        private boolean enabled = true;
        private long intervalMs = 300_000L;
        private long timeoutMs = 600_000L;

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

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
