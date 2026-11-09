package dev.cauce.channels.webchat;

/**
 * Port: checks a Cloudflare Turnstile token with the channel's secret before a session opens (ADR
 * 0014, WC2). The visitor's IP is never sent (ADR 0014, amendment).
 */
public interface TurnstileVerifier {

    /**
     * Whether {@code token} is a valid, unused Turnstile token for {@code secret}.
     *
     * @throws TurnstileUnavailableException if Turnstile cannot be asked: a session never opens
     *         unverified
     */
    boolean verify(String secret, String token);
}
