package dev.cauce.governance.audit;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Signature coverage over a verified chain, reported ALONGSIDE the chain verdict rather than
 * folded into it.
 *
 * <p>The separation is the point. Recomputation establishes one thing — that the stored
 * entries still hash and link as recorded — and that is what {@link ChainVerificationResult}'s
 * verdict states. Signatures establish a different thing, over a subset of entries, and this
 * report says exactly which subset. Folding "I could not check 4 signatures" into a boolean
 * would force a choice between two lies: calling the chain broken (it recomputes fine) or
 * calling it fully verified (four signatures were never checked).
 *
 * @param verified entries whose signature was checked against a known public key and matched
 * @param unsigned entries carrying no signature — v1 entries, or entries written by an
 *     instance with no signing key. Legitimate, never a failure
 * @param unverifiable entries carrying a signature this instance could not check
 * @param missingKeyIds the {@code key_id}s whose public key is absent from the registry, in
 *     first-seen order — what an operator needs in order to fix the gap
 * @param compromisedKeyIds {@code key_id}s that verified correctly but whose key the registry
 *     marks as compromised, mapped to how many entries they signed. Reported, never a
 *     failure: what to conclude belongs to whoever is auditing. The honest limit is that
 *     entries signed before the compromise cannot be told from entries signed after it
 */
public record SignatureReport(long verified,
                              long unsigned,
                              long unverifiable,
                              List<String> missingKeyIds,
                              Map<String, Long> compromisedKeyIds) {

    public SignatureReport {
        if (verified < 0 || unsigned < 0 || unverifiable < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
        missingKeyIds = List.copyOf(Objects.requireNonNull(missingKeyIds, "missingKeyIds"));
        compromisedKeyIds =
                Map.copyOf(Objects.requireNonNull(compromisedKeyIds, "compromisedKeyIds"));
    }

    /** The report of a chain with no entries at all. */
    public static SignatureReport empty() {
        return new SignatureReport(0, 0, 0, List.of(), Map.of());
    }

    /** Whether every chained entry carried a signature this instance could check. */
    public boolean fullyVerified() {
        return verified > 0 && unsigned == 0 && unverifiable == 0;
    }
}
