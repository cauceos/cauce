package dev.cauce.api.audit;

import dev.cauce.core.tenant.TenantContext;
import dev.cauce.governance.audit.ChainHead;
import dev.cauce.governance.audit.ChainVerificationService;
import dev.cauce.tenancy.TenantService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
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
 * <p>Optional anchor: {@code expected_head_sequence} and {@code expected_head_hash}, both or
 * neither, taken from the {@code head} of an earlier response. With it, a chain that no
 * longer reaches that point is reported {@code TRUNCATED}; without it, truncation is never
 * guessed.
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
    public ChainVerificationResponse verify(
            @PathVariable UUID tenantId,
            @RequestParam(name = "expected_head_sequence", required = false) Long expectedSequence,
            @RequestParam(name = "expected_head_hash", required = false) String expectedHash) {
        ChainHead anchor = anchorFrom(expectedSequence, expectedHash);
        // Resolve-or-404 under RLS BEFORE verifying: this is what makes an out-of-scope tenant
        // indistinguishable from a nonexistent one (throws TenantNotFoundException -> 404).
        tenantService.getTenant(tenantId);
        UUID actorTenantId = TenantContext.getCurrentTenantId().orElseThrow(
                () -> new IllegalStateException("no tenant context on an authenticated request"));
        return ChainVerificationResponse.from(tenantId,
                verificationService.verifyAndRecord(tenantId, anchor, actorTenantId),
                Instant.now());
    }

    /** Both parts or neither; half an anchor is a 400, not a guess. */
    private static ChainHead anchorFrom(Long sequence, String hash) {
        if (sequence == null && hash == null) {
            return null;
        }
        if (sequence == null || hash == null) {
            throw new IllegalArgumentException("expected_head_sequence and expected_head_hash "
                    + "must be supplied together");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("expected_head_sequence must be >= 1");
        }
        if (hash.isBlank()) {
            throw new IllegalArgumentException("expected_head_hash must not be blank");
        }
        return new ChainHead(sequence, hash);
    }
}
