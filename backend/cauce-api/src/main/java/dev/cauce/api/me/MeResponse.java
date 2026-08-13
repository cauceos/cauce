package dev.cauce.api.me;

import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.Tier;
import java.util.UUID;

/**
 * Identity of the API key making the request — the "who am I" body of {@code GET /v1/me}.
 * Everything here is what authentication already established about the caller: the
 * {@code tenantId} and {@code keyId} come straight from the authenticated principal, and
 * {@code tenantName}/{@code tier} from the caller's own (always self-visible) tenant. It
 * exposes nothing the key could not already reach — no visibility is widened.
 *
 * <p>Serialised in snake_case (global Jackson strategy); {@code tier} as its enum name.
 */
public record MeResponse(
        UUID tenantId,
        String tenantName,
        Tier tier,
        UUID keyId) {

    public static MeResponse from(Tenant tenant, UUID keyId) {
        return new MeResponse(tenant.id(), tenant.name(), tenant.tier(), keyId);
    }
}
