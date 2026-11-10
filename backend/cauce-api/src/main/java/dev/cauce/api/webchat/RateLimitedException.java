package dev.cauce.api.webchat;

import java.time.Duration;

/** A web-chat request over one of its limits (ADR 0014, WC3): 429, with how long to wait. */
public class RateLimitedException extends RuntimeException {

    private final transient WebChatRateLimiter.Bucket bucket;
    private final Duration retryAfter;

    public RateLimitedException(WebChatRateLimiter.Bucket bucket, Duration retryAfter) {
        super("Too many requests (" + bucket.name().toLowerCase() + "): try again in " + Math.max(1,
                retryAfter.toSeconds()) + " s");
        this.bucket = bucket;
        this.retryAfter = retryAfter;
    }

    public WebChatRateLimiter.Bucket bucket() {
        return bucket;
    }

    /** Whole seconds to wait, at least one. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
