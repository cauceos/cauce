package dev.cauce.governance.audit;

import dev.cauce.core.audit.AuditEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Family C of the audit vocabulary: <b>the ledger about itself</b>. Neither runtime conduct
 * (Family A, {@code conduct.*}) nor administration (Family B, {@code admin.*}).
 *
 * <p>Its first and so far only event, {@link #CHAIN_VERIFIED}, records that someone verified a
 * tenant's chain: who (the acting tenant, ADR 0002), when (the drain timestamp, like every
 * entry), and what they found. It is written through the same outbox path as every other
 * event and lands in the SUBJECT tenant's chain.
 *
 * <p><b>Recursive by design.</b> Once drained, the verification entry is part of the chain,
 * so the next verification checks it too and counts it. If the ledger says the chain
 * verified as valid on a given date, any later manipulation is bounded in time — a weak
 * internal anchor, not binding on a party who distrusts the operator, but free.
 *
 * <p><b>On demand only.</b> This event must never be attached to a scheduler or to a write
 * path: a ledger that verifies itself on a timer grows without adding information, and each
 * automatic entry would be an entry nobody asked for. The only emitter is
 * {@link ChainVerificationService#verifyAndRecord}, and it is only reached from the public
 * verification endpoint.
 *
 * <p>Payload doctrine as everywhere: non-sensitive metadata only. Every value here is either
 * an id, a count, a verdict, or a hash that already sits in the ledger.
 */
public final class LedgerAuditEvents {

    public static final String CHAIN_VERIFIED = "ledger.chain.verified";

    private LedgerAuditEvents() {
    }

    /** The record of one on-demand verification of {@code tenantId}'s chain. */
    public static AuditEvent chainVerified(UUID tenantId, UUID actorTenantId,
                                           ChainVerificationResult result) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actor_tenant_id", actorTenantId.toString());
        payload.put("verdict", result.verdict().name());
        payload.put("chained_count", result.chainedCount());
        payload.put("pre_chain_count", result.preChainCount());
        if (result.head() != null) {
            payload.put("head_sequence", result.head().sequenceNumber());
            payload.put("head_entry_hash", result.head().entryHash());
        }
        if (result.brokenAtSequence() != null) {
            payload.put("broken_at_sequence", result.brokenAtSequence());
            payload.put("break_kind", result.breakKind().name());
        }
        if (result.unverifiableFromSequence() != null) {
            payload.put("unverifiable_from_sequence", result.unverifiableFromSequence());
        }
        SignatureReport signatures = result.signatures();
        payload.put("signatures_verified", signatures.verified());
        payload.put("signatures_unsigned", signatures.unsigned());
        payload.put("signatures_unverifiable", signatures.unverifiable());
        payload.put("missing_key_ids", signatures.missingKeyIds());
        payload.put("compromised_key_ids", signatures.compromisedKeyIds().keySet().stream()
                .toList());
        return new AuditEvent(tenantId, CHAIN_VERIFIED, payload);
    }
}
