package dev.cauce.api.audit;

import dev.cauce.governance.audit.AuditChainVerifier;
import dev.cauce.tenancy.TenantService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for audit chain verification. Thin: it resolves the tenant first (so an id
 * outside the caller's hierarchy is indistinguishable from a nonexistent one — RLS visibility,
 * a 404, never a "200 valid empty" leak about someone else's tenant), then delegates to the
 * internal {@link AuditChainVerifier} and maps the {@code ChainVerificationResult} to the
 * public {@link ChainVerificationResponse}. It wraps the verifier; it does not reimplement it.
 *
 * <p>Tenant context is derived from the validated API key by {@code ApiKeyAuthenticationFilter};
 * a partner can therefore verify a visible client's chain, but no one can reach a chain outside
 * their hierarchical visibility.
 *
 * <p><strong>Known limit.</strong> Verification is full and on demand: it recomputes the whole
 * chain, so it is O(n) in time and memory over the tenant's entries. There is no cache and no
 * incremental mode; both are deferred.
 */
@RestController
public class ChainVerificationController {

    private final TenantService tenantService;
    private final AuditChainVerifier verifier;

    public ChainVerificationController(TenantService tenantService, AuditChainVerifier verifier) {
        this.tenantService = tenantService;
        this.verifier = verifier;
    }

    @GetMapping("/v1/tenants/{tenantId}/audit/chain-verification")
    public ChainVerificationResponse verify(@PathVariable UUID tenantId) {
        // Resolve-or-404 under RLS BEFORE verifying: this is what makes an out-of-scope tenant
        // indistinguishable from a nonexistent one (throws TenantNotFoundException -> 404).
        tenantService.getTenant(tenantId);
        return ChainVerificationResponse.from(tenantId, verifier.verifyChain(tenantId),
                Instant.now());
    }
}
