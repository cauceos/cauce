package dev.cauce.governance.audit;

import java.util.Objects;

/**
 * Outcome of verifying one tenant's audit chain: either every chained entry recomputed and
 * linked correctly ({@code valid}), or the sequence number of the FIRST break and what kind
 * of break it is. {@code preChainCount} reports rows drained before the chain unit
 * (unverifiable by construction, legal only as a prefix, never backfilled) — honesty about
 * what the chain does and does not cover.
 *
 * <p>{@code signatures} reports signature coverage separately from the verdict. The verdict is
 * the verdict of the RECOMPUTATION; a signature this instance could not check does not make a
 * chain broken, and must not be silently counted as verified either. See
 * {@link SignatureReport}.
 */
public record ChainVerificationResult(boolean valid,
                                      long chainedCount,
                                      long preChainCount,
                                      Long brokenAtSequence,
                                      ChainBreakKind breakKind,
                                      SignatureReport signatures) {

    public ChainVerificationResult {
        if (valid && (brokenAtSequence != null || breakKind != null)) {
            throw new IllegalArgumentException("a valid result cannot carry a break");
        }
        if (!valid) {
            Objects.requireNonNull(brokenAtSequence, "brokenAtSequence must not be null");
            Objects.requireNonNull(breakKind, "breakKind must not be null");
        }
        if (chainedCount < 0 || preChainCount < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
        Objects.requireNonNull(signatures, "signatures must not be null");
    }

    /** A fully verified chain (trivially valid when the tenant has no entries at all). */
    public static ChainVerificationResult valid(long chainedCount, long preChainCount,
                                                SignatureReport signatures) {
        return new ChainVerificationResult(true, chainedCount, preChainCount, null, null,
                signatures);
    }

    /** A fully verified chain whose signature coverage is not being reported. */
    public static ChainVerificationResult valid(long chainedCount, long preChainCount) {
        return valid(chainedCount, preChainCount, SignatureReport.empty());
    }

    /** A chain broken first at {@code brokenAtSequence}. */
    public static ChainVerificationResult broken(long brokenAtSequence, ChainBreakKind breakKind,
                                                 long chainedCount, long preChainCount,
                                                 SignatureReport signatures) {
        return new ChainVerificationResult(false, chainedCount, preChainCount, brokenAtSequence,
                breakKind, signatures);
    }

    /** A broken chain whose signature coverage is not being reported. */
    public static ChainVerificationResult broken(long brokenAtSequence, ChainBreakKind breakKind,
                                                 long chainedCount, long preChainCount) {
        return broken(brokenAtSequence, breakKind, chainedCount, preChainCount,
                SignatureReport.empty());
    }
}
