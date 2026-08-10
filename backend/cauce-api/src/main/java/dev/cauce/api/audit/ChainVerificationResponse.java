package dev.cauce.api.audit;

import dev.cauce.governance.audit.ChainVerificationResult;
import java.time.Instant;
import java.util.UUID;

/**
 * Public view of a tenant's audit chain verification. Wraps the internal
 * {@link ChainVerificationResult} into the stable wire contract: the computational fact
 * (valid, or the exact first break), the honest count of pre-chain rows the chain does not
 * cover, the {@link VerificationScope} on every response, and when the check ran.
 */
public record ChainVerificationResponse(UUID tenantId,
                                        ChainStatus status,
                                        long verifiedEntries,
                                        long preChainEntries,
                                        FirstBreak firstBreak,
                                        VerificationScope verificationScope,
                                        Instant verifiedAt) {

    public static ChainVerificationResponse from(UUID tenantId, ChainVerificationResult result,
                                                 Instant verifiedAt) {
        FirstBreak firstBreak = result.valid() ? null
                : new FirstBreak(result.brokenAtSequence(),
                        BreakClassification.from(result.breakKind()));
        return new ChainVerificationResponse(
                tenantId,
                result.valid() ? ChainStatus.VALID : ChainStatus.BROKEN,
                result.chainedCount(),
                result.preChainCount(),
                firstBreak,
                VerificationScope.CONSTANT,
                verifiedAt);
    }
}
