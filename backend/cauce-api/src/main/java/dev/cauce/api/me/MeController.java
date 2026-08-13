package dev.cauce.api.me;

import dev.cauce.api.security.ApiKeyAuthenticationToken;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.tenancy.TenantService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Key introspection: {@code GET /v1/me} answers "who am I according to this key". No
 * parameters — the identity is derived entirely from the authenticated principal.
 *
 * <p>{@code tenantId} and {@code keyId} are read directly from {@link ApiKeyAuthenticationToken}
 * (set by {@code ApiKeyAuthenticationFilter} from the validated key), so they cost no lookup.
 * {@code tier} and the tenant name come from a single {@link TenantService#getTenant} on the
 * caller's <em>own</em> tenant — always visible to itself under RLS, so this widens no
 * visibility beyond what authentication already granted.
 */
@RestController
public class MeController {

    private final TenantService tenantService;

    public MeController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping("/v1/me")
    public MeResponse me(Authentication authentication) {
        ApiKeyAuthenticationToken principal = (ApiKeyAuthenticationToken) authentication;
        Tenant tenant = tenantService.getTenant(principal.tenantId());
        return MeResponse.from(tenant, principal.apiKeyId());
    }
}
