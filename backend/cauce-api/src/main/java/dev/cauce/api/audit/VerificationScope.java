package dev.cauce.api.audit;

import java.util.List;

/**
 * Plain-language description of what the chain verification does and does not cover, returned
 * on every response (valid or broken) so the outcome is never read as more than it is.
 *
 * <p>{@link #doesNotDetect} names the known residual — a wholesale rewrite by a
 * database-owner-privileged actor that re-chains the suffix and rewrites the head — as a
 * capability limit, not fine print: recomputation alone cannot distinguish it, and the
 * per-entry signature that would is reserved and not yet enabled.
 */
public record VerificationScope(String method, List<String> detects, String doesNotDetect) {

    /** The single, constant scope shared by every response. */
    public static final VerificationScope CONSTANT = new VerificationScope(
            "Each entry is re-hashed from its stored fields and compared, in sequence order, "
                    + "against the recorded entry hash and payload hash, and its link is checked "
                    + "against the preceding entry's hash, starting from a hash derived from the "
                    + "tenant id.",
            List.of(
                    "Modification of a stored entry's content or metadata after it was chained.",
                    "Removal or reordering of an entry inside the chain.",
                    "A gap in the per-tenant sequence where a chained entry is no longer present.",
                    "An entry inserted into the chain without valid chain hashes."),
            "A wholesale rewrite by an actor holding database-owner privileges that recomputes "
                    + "every entry hash from the point of change onward and rewrites the chain "
                    + "head. Recomputation alone cannot distinguish this from an honest chain; "
                    + "telling the two apart requires the per-entry signature, which is reserved "
                    + "and not yet enabled.");
}
