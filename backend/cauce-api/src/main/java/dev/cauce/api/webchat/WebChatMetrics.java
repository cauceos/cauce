package dev.cauce.api.webchat;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The public web-chat surface's meters (ADR 0014; ADR 0011), what the operator's alerts read: every
 * response by its status code, and each request refused because the request limiter could not be asked.
 * Tags are the status code only, a bounded set; never a channel, a session or an IP.
 */
@Component
public class WebChatMetrics {

    static final String RESPONSES = "cauce.web.responses";
    static final String LIMITER_UNAVAILABLE = "cauce.web.rate_limit.unavailable";

    private final MeterRegistry registry;

    public WebChatMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One response of the surface, by its status code. */
    public void response(int status) {
        registry.counter(RESPONSES, "status", String.valueOf(status)).increment();
    }

    /** One request refused because Redis could not be asked (503 {@code rate_limit_unavailable}). */
    public void limiterUnavailable() {
        registry.counter(LIMITER_UNAVAILABLE).increment();
    }
}
