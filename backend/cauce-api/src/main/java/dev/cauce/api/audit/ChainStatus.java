package dev.cauce.api.audit;

/**
 * Public outcome of verifying a tenant's audit chain. {@link #VALID} means every chained
 * entry recomputed and linked consistently; {@link #BROKEN} means recomputation found a
 * defect, whose location and kind are carried by {@link FirstBreak}.
 *
 * <p>"Valid" is a statement about recomputation only — it says the chain has no break
 * detectable by re-hashing. What that does and does not cover is spelled out in
 * {@link VerificationScope} on every response.
 */
public enum ChainStatus {
    VALID,
    BROKEN
}
