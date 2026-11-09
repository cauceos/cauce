package dev.cauce.channels.webchat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The web-chat channel's platform settings (ADR 0014), bound from {@code cauce.web.*}. */
@ConfigurationProperties(prefix = "cauce.web")
public class WebChatProperties {

    /** Cloudflare's Turnstile verification endpoint (overridable for tests). */
    private String turnstileVerifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    /** How long a Turnstile verification may take before the session is refused as unavailable. */
    private Duration turnstileTimeout = Duration.ofSeconds(5);

    /** A session's sliding lifetime, in days (ADR 0014, WC2). */
    private int sessionDays = 30;

    public String getTurnstileVerifyUrl() {
        return turnstileVerifyUrl;
    }

    public void setTurnstileVerifyUrl(String turnstileVerifyUrl) {
        this.turnstileVerifyUrl = turnstileVerifyUrl;
    }

    public Duration getTurnstileTimeout() {
        return turnstileTimeout;
    }

    public void setTurnstileTimeout(Duration turnstileTimeout) {
        this.turnstileTimeout = turnstileTimeout;
    }

    public int getSessionDays() {
        return sessionDays;
    }

    public void setSessionDays(int sessionDays) {
        this.sessionDays = sessionDays;
    }
}
