package dev.cauce.api.audit;

import dev.cauce.governance.audit.ChainVerificationResult;
import java.time.Instant;
import java.util.UUID;

/**
 * Public view of a tenant's audit chain verification. Wraps the internal
 * {@link ChainVerificationResult} into the stable wire contract: the four-state
 * {@link ChainStatus}, the counts, the exact first break when there is one, the chain head
 * (to keep and pass back next time), the caller's {@code expected_head} echoed, where the walk
 * had to stop if it did, the {@link SignatureSummary}, the {@link VerificationScope} on every
 * response, and when the check ran.
 *
 * <p>Every verification through the public endpoint is itself recorded in the chain it
 * verified, one drainer tick later. {@code verified_entries} on the NEXT call therefore
 * includes this one's record.
 */
public record ChainVerificationResponse(UUID tenantId,
                                        ChainStatus status,
                                        long verifiedEntries,
                                        long preChainEntries,
                                        FirstBreak firstBreak,
                                        ChainHeadResponse head,
                                        ChainHeadResponse expectedHead,
                                        Long unverifiableFromSequence,
                                        SignatureSummary signatures,
                                        VerificationScope verificationScope,
                                        Instant verifiedAt) {

    public static ChainVerificationResponse from(UUID tenantId, ChainVerificationResult result,
                                                 Instant verifiedAt) {
        FirstBreak firstBreak = result.brokenAtSequence() == null ? null
                : new FirstBreak(result.brokenAtSequence(),
                        BreakClassification.from(result.breakKind()));
        return new ChainVerificationResponse(
                tenantId,
                ChainStatus.from(result.verdict()),
                result.chainedCount(),
                result.preChainCount(),
                firstBreak,
                ChainHeadResponse.from(result.head()),
                ChainHeadResponse.from(result.expectedHead()),
                result.unverifiableFromSequence(),
                SignatureSummary.from(result.signatures()),
                VerificationScope.CONSTANT,
                verifiedAt);
    }
}
