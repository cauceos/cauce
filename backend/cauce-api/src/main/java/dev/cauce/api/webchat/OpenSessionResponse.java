package dev.cauce.api.webchat;

import java.time.Instant;

/**
 * A session just opened: its token, shown once (the widget keeps it in the shop page's
 * {@code localStorage}, ADR 0014, WC2), and when it expires unless used.
 */
public record OpenSessionResponse(String sessionToken, Instant expiresAt) {
}
