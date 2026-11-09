package dev.cauce.api.webchat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /web/v1/{publicKey}/sessions} (ADR 0014, WC2): the Turnstile token the widget
 * got, and the person's acceptance when the channel asks for it.
 */
public record OpenSessionRequest(@NotBlank @Size(max = 2048) String turnstileToken, Boolean consent) {
}
