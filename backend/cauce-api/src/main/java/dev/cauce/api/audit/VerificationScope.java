package dev.cauce.api.audit;

import java.util.List;

/**
 * Plain-language description of what the chain verification does and does not cover, returned
 * on every response (whatever the status) so the outcome is never read as more than it is.
 *
 * <p>Rendered verbatim by clients, so this IS public surface. It states the current
 * capability — recomputation plus per-entry signatures where they exist — and names the
 * residuals as capability limits, not fine print: an actor holding the signing key, entries
 * that carry no signature, and a chain shortened to a consistent earlier state.
 * The compliance-vocabulary guard in {@code ChainVerificationApiIT} runs over every field.
 */
public record VerificationScope(String method, List<String> detects, String doesNotDetect) {

    /** The single, constant scope shared by every response. */
    public static final VerificationScope CONSTANT = new VerificationScope(
            "Each entry is re-hashed from its stored fields and compared, in sequence order, "
                    + "against the recorded entry hash and payload hash, and its link is checked "
                    + "against the preceding entry's hash, starting from a hash derived from the "
                    + "tenant id. Where an entry carries a signature, it is checked against the "
                    + "public key its key id names in a registry published outside the database.",
            List.of(
                    "Modification of a stored entry's content or metadata after it was chained.",
                    "Removal or reordering of an entry inside the chain.",
                    "A gap in the per-tenant sequence where a chained entry is no longer present.",
                    "An entry inserted into the chain without valid chain hashes.",
                    "Among signed entries, a rewrite that recomputes every hash from the point of "
                            + "change onward: producing a matching signature requires the private "
                            + "key, which the database does not contain."),
            "A rewrite by an actor who holds the signing key: anyone with access to the "
                    + "deployment's configuration, not only to the database. Entries without a "
                    + "signature, written before signing existed or by an instance with no key "
                    + "configured, are covered by recomputation alone, which cannot tell such a "
                    + "rewrite from an honest chain; the signature summary on this response says "
                    + "how many entries are in that position. A chain shortened to a consistent "
                    + "earlier state cannot be told from one that was never longer: nothing inside "
                    + "the database can serve as the reference, so the head reported here is the "
                    + "value such a reference would have to be kept from, elsewhere.");
}
