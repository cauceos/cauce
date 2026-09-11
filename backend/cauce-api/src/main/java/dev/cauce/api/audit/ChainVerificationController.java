package dev.cauce.api.audit;

import dev.cauce.core.tenant.TenantContext;
import dev.cauce.governance.audit.ChainVerificationService;
import dev.cauce.tenancy.TenantService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for audit chain verification. Thin: it resolves the tenant first (so an id
 * outside the caller's hierarchy is indistinguishable from a nonexistent one — RLS visibility,
 * a 404, never a "200 valid empty" leak about someone else's tenant), then delegates to
 * {@link ChainVerificationService} and maps the result to the public
 * {@link ChainVerificationResponse}. It wraps the verifier; it does not reimplement it.
 *
 * <p>Tenant context is derived from the validated API key by {@code ApiKeyAuthenticationFilter};
 * a partner can therefore verify a visible client's chain, but no one can reach a chain outside
 * their hierarchical visibility. The acting tenant is recorded as the verifier.
 *
 * <p><strong>Every call is recorded in the chain it verifies</strong> (ADR 0003 §5), one
 * drainer tick later. That is why this endpoint is the ONLY caller of
 * {@code verifyAndRecord}, and why it must stay on demand: never poll it, never attach it to
 * a scheduler. A chain that verifies itself on a timer grows without adding information.
 *
 * <p>The response carries the chain {@code head} but the endpoint takes no earlier head back:
 * a chain shortened to a consistent earlier state cannot be told, from inside the database,
 * from one that was never longer, and comparing against a reference held elsewhere belongs to
 * external anchoring, not to this contract.
 *
 * <p><strong>Known limit.</strong> Verification is full: it recomputes the whole chain, so it
 * is O(n) in time and memory over the tenant's entries, plus one signature check per signed
 * entry. There is no cache and no incremental mode; both are deferred.
 */
@RestController
public class ChainVerificationController {

    private final TenantService tenantService;
    private final ChainVerificationService verificationService;

    public ChainVerificationController(TenantService tenantService,
                                       ChainVerificationService verificationService) {
        this.tenantService = tenantService;
        this.verificationService = verificationService;
    }

    @GetMapping("/v1/tenants/{tenantId}/audit/chain-verification")
    public ChainVerificationResponse verify(@PathVariable UUID tenantId) {
        // Resolve-or-404 under RLS BEFORE verifying: this is what makes an out-of-scope tenant
        // indistinguishable from a nonexistent one (throws TenantNotFoundException -> 404).
        tenantService.getTenant(tenantId);
        UUID actorTenantId = TenantContext.getCurrentTenantId().orElseThrow(
                () -> new IllegalStateException("no tenant context on an authenticated request"));
        return ChainVerificationResponse.from(tenantId,
                verificationService.verifyAndRecord(tenantId, actorTenantId),
                Instant.now());
    }
}
