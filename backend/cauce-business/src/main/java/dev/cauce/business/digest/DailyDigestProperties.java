package dev.cauce.business.digest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The nightly summary scheduler (ADR 0007, B10):
 *
 * <pre>
 * cauce.business.digest.enabled = true       # the scheduled run
 * cauce.business.digest.interval-ms = 60000  # @Scheduled fixed delay
 * </pre>
 */
@ConfigurationProperties("cauce.business.digest")
public class DailyDigestProperties {

    private boolean enabled = true;
    private long intervalMs = 60_000;

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
}
