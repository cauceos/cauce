package dev.cauce.api.webchat;

import dev.cauce.channels.webchat.WebChatLimits;
import dev.cauce.channels.webchat.WebChatProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * The web chat's request limits in Redis (ADR 0014, WC3): a counter per bucket, subject and fixed window,
 * incremented and given its lifetime in one atomic script, so a counter never outlives its window. A
 * request over a limit is refused with how long to wait. The subject is a session's id or an IP's HMAC
 * ({@link ClientIps}), never an IP.
 *
 * <p>Fixed windows, not the token buckets of WC3: the same bounds per window, at most twice the rate
 * across a window's edge, with one counter per subject instead of a stored rate. Redis down refuses the
 * request ({@link RateLimitUnavailableException}): this surface is open to anonymous traffic, and an
 * unlimited one would be worse than an unavailable one.
 */
@Component
public class WebChatRateLimiter {

    /** INCR, and on the first hit PEXPIRE; returns the count and the window's remaining milliseconds. */
    private static final DefaultRedisScript<List> HIT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return {c, redis.call('PTTL', KEYS[1])}", List.class);

    /** The buckets of WC3, each with its window. */
    public enum Bucket {
        SESSIONS_PER_IP(Duration.ofHours(1)),
        MESSAGES_PER_SESSION_MINUTE(Duration.ofMinutes(1)),
        MESSAGES_PER_SESSION_DAY(Duration.ofDays(1)),
        MESSAGES_PER_IP_MINUTE(Duration.ofMinutes(1)),
        READS_PER_SESSION_MINUTE(Duration.ofMinutes(1));

        private final Duration window;

        Bucket(Duration window) {
            this.window = window;
        }

        int limit(WebChatLimits limits) {
            return switch (this) {
                case SESSIONS_PER_IP -> limits.sessionsPerIpHour();
                case MESSAGES_PER_SESSION_MINUTE -> limits.messagesPerSessionMinute();
                case MESSAGES_PER_SESSION_DAY -> limits.messagesPerSessionDay();
                case MESSAGES_PER_IP_MINUTE -> limits.messagesPerIpMinute();
                case READS_PER_SESSION_MINUTE -> limits.readsPerSessionMinute();
            };
        }
    }

    private final StringRedisTemplate redis;
    private final WebChatProperties properties;
    private final WebChatMetrics metrics;

    public WebChatRateLimiter(StringRedisTemplate redis, WebChatProperties properties, WebChatMetrics metrics) {
        this.redis = redis;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Counts one request of {@code subject} in {@code bucket} on the channel {@code channelConfigId}, under
     * the channel's limits in force.
     *
     * @throws RateLimitedException if it is over the limit
     * @throws RateLimitUnavailableException if Redis cannot be asked
     */
    public void hit(UUID channelConfigId, WebChatLimits channelLimits, Bucket bucket, String subject) {
        int limit = bucket.limit(channelLimits.effective(properties.limits()));
        String key = "cauce:web:rl:" + bucket.name().toLowerCase() + ":" + channelConfigId + ":" + subject;
        List<?> result;
        try {
            result = redis.execute(HIT, List.of(key), String.valueOf(bucket.window.toMillis()));
        } catch (DataAccessException e) {
            metrics.limiterUnavailable(); // the operator is alerted (cauce-web-limiter-down)
            throw new RateLimitUnavailableException("The request limits cannot be checked", e);
        }
        long count = ((Number) result.get(0)).longValue();
        long remainingMillis = Math.max(0, ((Number) result.get(1)).longValue());
        if (count > limit) {
            throw new RateLimitedException(bucket, Duration.ofMillis(remainingMillis));
        }
    }
}
