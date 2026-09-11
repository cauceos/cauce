package dev.cauce.governance.audit;

import java.util.Objects;

/**
 * Outcome of verifying one tenant's audit chain: a {@link ChainVerdict}, the counts, the
 * exact first break when there is one, the chain's current head, and signature coverage.
 *
 * <p>The verdict is the verdict of the RECOMPUTATION plus what could honestly be checked on
 * top of it. Signature coverage is reported separately in {@code signatures}: a signature
 * this instance could not check does not make a chain broken, and must not be silently
 * counted as verified either — it makes the verdict {@link ChainVerdict#UNVERIFIABLE}.
 *
 * <p>{@code preChainCount} reports rows drained before the chain unit (unverifiable by
 * construction, legal only as a prefix, never backfilled). {@code head} is what a caller
 * keeps and passes back as the anchor next time; {@code expectedHead} echoes the anchor it
 * passed, when it did. {@code unverifiableFromSequence} is set when the walk had to stop at an
 * entry whose hash scheme this build does not implement — everything from there on is
 * unchecked, and said so.
 *
 * @param verdict the four-state outcome
 * @param chainedCount entries verified, up to the break or the unverifiable point
 * @param preChainCount rows drained before the chain existed, reported honestly, never checked
 * @param brokenAtSequence the first break's sequence; {@link ChainVerdict#BROKEN} only
 * @param breakKind the first break's kind; {@link ChainVerdict#BROKEN} only
 * @param head the last chained entry, or null when the chain has none
 * @param expectedHead the caller's anchor, or null when none was supplied
 * @param unverifiableFromSequence where the walk stopped on an unknown hash scheme, or null
 * @param signatures signature coverage over the entries walked
 */
public record ChainVerificationResult(ChainVerdict verdict,
                                      long chainedCount,
                                      long preChainCount,
                                      Long brokenAtSequence,
                                      ChainBreakKind breakKind,
                                      ChainHead head,
                                      ChainHead expectedHead,
                                      Long unverifiableFromSequence,
                                      SignatureReport signatures) {

    public ChainVerificationResult {
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(signatures, "signatures must not be null");
        boolean broken = verdict == ChainVerdict.BROKEN;
        if (broken) {
            Objects.requireNonNull(brokenAtSequence, "brokenAtSequence must not be null");
            Objects.requireNonNull(breakKind, "breakKind must not be null");
        } else if (brokenAtSequence != null || breakKind != null) {
            throw new IllegalArgumentException("only a broken result can carry a break");
        }
        if (verdict == ChainVerdict.TRUNCATED && expectedHead == null) {
            throw new IllegalArgumentException(
                    "a truncated verdict is only ever issued against a caller-supplied anchor");
        }
        if (chainedCount < 0 || preChainCount < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
    }

    /** Whether the verdict is {@link ChainVerdict#VALID}. */
    public boolean valid() {
        return verdict == ChainVerdict.VALID;
    }

    /** A fully verified chain with nothing further to report (trivially so when empty). */
    public static ChainVerificationResult valid(long chainedCount, long preChainCount) {
        return new ChainVerificationResult(ChainVerdict.VALID, chainedCount, preChainCount, null,
                null, null, null, null, SignatureReport.empty());
    }

    /** A chain broken first at {@code brokenAtSequence}, with nothing further to report. */
    public static ChainVerificationResult broken(long brokenAtSequence, ChainBreakKind breakKind,
                                                 long chainedCount, long preChainCount) {
        return new ChainVerificationResult(ChainVerdict.BROKEN, chainedCount, preChainCount,
                brokenAtSequence, breakKind, null, null, null, SignatureReport.empty());
    }
}
