package dev.cauce.tenancy.apikey;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for {@link ApiKeyCache}.
 *
 * <pre>
 * cauce.security.api-key-cache.ttl-seconds       = 300   # entry lifetime (default 5 min)
 * cauce.security.api-key-cache.max-size          = 10000 # hard upper bound on entries
 * </pre>
 */
@ConfigurationProperties(prefix = "cauce.security.api-key-cache")
public class ApiKeyCacheProperties {

    private long ttlSeconds = 300L;
    private long maxSize = 10_000L;

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public long getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
    }
}
