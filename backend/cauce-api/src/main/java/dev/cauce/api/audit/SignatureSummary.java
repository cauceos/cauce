package dev.cauce.api.audit;

import dev.cauce.governance.audit.SignatureReport;
import java.util.List;
import java.util.Map;

/**
 * Signature coverage over the entries walked, reported alongside the status rather than
 * folded into it. Three buckets that add up to the verified entry count, plus what an
 * operator needs in order to act on the unverifiable ones.
 *
 * @param verified entries whose signature was checked against a published key and matched
 * @param unsigned entries carrying no signature — see {@code note}; never a defect
 * @param unverifiable entries carrying a signature this instance could not check
 * @param missingKeyIds key ids named by unverifiable entries whose public key is not published
 *     to this instance — the gap to fix
 * @param compromisedKeyIds key ids that verified correctly but are marked compromised in the
 *     registry, with how many entries each signed. Reported, not judged
 * @param note the standing explanation of what an unsigned entry is
 */
public record SignatureSummary(long verified,
                               long unsigned,
                               long unverifiable,
                               List<String> missingKeyIds,
                               Map<String, Long> compromisedKeyIds,
                               String note) {

    /** Renders verbatim wherever the summary is shown; part of the public surface. */
    public static final String NOTE = "Entries without a signature are not a defect: signing "
            + "did not exist when they were written, or the instance that wrote them had no "
            + "signing key configured. Such entries are checked by recomputation only.";

    static SignatureSummary from(SignatureReport report) {
        return new SignatureSummary(report.verified(), report.unsigned(), report.unverifiable(),
                report.missingKeyIds(), report.compromisedKeyIds(), NOTE);
    }
}
