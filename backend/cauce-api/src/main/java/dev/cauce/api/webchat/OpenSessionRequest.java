package dev.cauce.api.webchat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /web/v1/{publicKey}/sessions} (ADR 0014, WC2): the Turnstile token the widget
 * got, and, when the channel asks for acceptance, the person's acceptance and the version of the
 * widget's notice text they accepted.
 */
public record OpenSessionRequest(@NotBlank @Size(max = 2048) String turnstileToken, Boolean consent,
                                 @Size(max = 32) String noticeVersion) {
}
