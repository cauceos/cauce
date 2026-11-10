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

    /** The platform's request limits; a channel may only lower them (ADR 0014, WC3). */
    private int sessionsPerIpHour = WebChatLimits.PLATFORM.sessionsPerIpHour();
    private int messagesPerSessionMinute = WebChatLimits.PLATFORM.messagesPerSessionMinute();
    private int messagesPerSessionDay = WebChatLimits.PLATFORM.messagesPerSessionDay();
    private int messagesPerIpMinute = WebChatLimits.PLATFORM.messagesPerIpMinute();
    private int readsPerSessionMinute = WebChatLimits.PLATFORM.readsPerSessionMinute();

    /**
     * The key IPs are hashed with for the request limits (base64, 32 bytes), outside the database: an IP is
     * never stored or logged (ADR 0014, amendment). Without one, a random key per process is used, which
     * keeps limits per instance only.
     */
    private String ipHashKey;

    /**
     * Proxies whose {@code CF-Connecting-IP} header is trusted as the visitor's IP (exact addresses);
     * from any other address, the socket's address is the visitor's.
     */
    private java.util.List<String> trustedProxies = java.util.List.of();

    /**
     * Where the instance serves the web widget from (ADR 0014, W4): a directory laid out as the widget's
     * build lays it, {@code {version}/cauce-widget.js}. Unset, the instance serves no widget.
     */
    private String widgetDir;

    public String getWidgetDir() {
        return widgetDir;
    }

    public void setWidgetDir(String widgetDir) {
        this.widgetDir = widgetDir;
    }

    /** The platform's request limits. */
    public WebChatLimits limits() {
        return new WebChatLimits(sessionsPerIpHour, messagesPerSessionMinute, messagesPerSessionDay,
                messagesPerIpMinute, readsPerSessionMinute);
    }

    public int getSessionsPerIpHour() {
        return sessionsPerIpHour;
    }

    public void setSessionsPerIpHour(int sessionsPerIpHour) {
        this.sessionsPerIpHour = sessionsPerIpHour;
    }

    public int getMessagesPerSessionMinute() {
        return messagesPerSessionMinute;
    }

    public void setMessagesPerSessionMinute(int messagesPerSessionMinute) {
        this.messagesPerSessionMinute = messagesPerSessionMinute;
    }

    public int getMessagesPerSessionDay() {
        return messagesPerSessionDay;
    }

    public void setMessagesPerSessionDay(int messagesPerSessionDay) {
        this.messagesPerSessionDay = messagesPerSessionDay;
    }

    public int getMessagesPerIpMinute() {
        return messagesPerIpMinute;
    }

    public void setMessagesPerIpMinute(int messagesPerIpMinute) {
        this.messagesPerIpMinute = messagesPerIpMinute;
    }

    public int getReadsPerSessionMinute() {
        return readsPerSessionMinute;
    }

    public void setReadsPerSessionMinute(int readsPerSessionMinute) {
        this.readsPerSessionMinute = readsPerSessionMinute;
    }

    public String getIpHashKey() {
        return ipHashKey;
    }

    public void setIpHashKey(String ipHashKey) {
        this.ipHashKey = ipHashKey;
    }

    public java.util.List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(java.util.List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null ? java.util.List.of() : java.util.List.copyOf(trustedProxies);
    }

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
