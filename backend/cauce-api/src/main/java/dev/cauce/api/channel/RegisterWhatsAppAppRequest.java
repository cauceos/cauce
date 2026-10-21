package dev.cauce.api.channel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /v1/tenants/{tenantId}/whatsapp-apps}: Meta's id of the app and its app
 * secret (App Dashboard → App settings → Basic). The secret is stored sealed and never returned.
 */
public record RegisterWhatsAppAppRequest(
        @NotBlank @Size(max = 64) String metaAppId,
        @NotBlank @Size(max = 256) String appSecret) {
}
