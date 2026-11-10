package dev.cauce.api.webchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.cauce.channels.webchat.WebChatLimits;
import dev.cauce.channels.webchat.WebChatProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class WebChatMetricsTest {

    @Test
    void aResponse_isCountedByItsStatus_andAnUnreachableLimiter_isCountedAndRefused() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WebChatMetrics metrics = new WebChatMetrics(registry);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));
        WebChatRateLimiter limiter = new WebChatRateLimiter(redis, new WebChatProperties(), metrics);

        metrics.response(503);
        metrics.response(503);
        metrics.response(202);
        assertThatThrownBy(() -> limiter.hit(UUID.randomUUID(), WebChatLimits.NONE,
                WebChatRateLimiter.Bucket.MESSAGES_PER_IP_MINUTE, "subject"))
                .isInstanceOf(RateLimitUnavailableException.class);

        assertThat(registry.counter(WebChatMetrics.RESPONSES, "status", "503").count()).isEqualTo(2.0);
        assertThat(registry.counter(WebChatMetrics.RESPONSES, "status", "202").count()).isEqualTo(1.0);
        assertThat(registry.counter(WebChatMetrics.LIMITER_UNAVAILABLE).count()).isEqualTo(1.0);
    }
}
