package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import dev.cauce.core.audit.AuditEvent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerAuditEventsTest {

    /** The same nine terms the API and the playground guard against: the ledger is surface too. */
    private static final List<String> FORBIDDEN = List.of(
            "compliant", "gdpr", "legal", "court", "admissible",
            "certified", "guaranteed", "tamper-proof", "protected");

    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @Test
    void chainVerified_validResult_recordsVerdictCountsHeadAndActorInTheSubjectChain() {
        ChainHead head = new ChainHead(31, "h".repeat(64));
        ChainVerificationResult result = new ChainVerificationResult(ChainVerdict.VALID, 31, 0,
                null, null, head, null,
                new SignatureReport(20, 11, 0, List.of(), Map.of()));

        AuditEvent event = LedgerAuditEvents.chainVerified(tenantId, actorId, result);

        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.eventType()).isEqualTo("ledger.chain.verified");
        assertThat(event.payload()).contains(
                entry("actor_tenant_id", actorId.toString()),
                entry("verdict", "VALID"),
                entry("chained_count", 31L),
                entry("pre_chain_count", 0L),
                entry("head_sequence", 31L),
                entry("head_entry_hash", "h".repeat(64)),
                entry("signatures_verified", 20L),
                entry("signatures_unsigned", 11L),
                entry("signatures_unverifiable", 0L));
        assertThat(event.payload()).doesNotContainKeys("broken_at_sequence", "break_kind",
                "unverifiable_from_sequence");
    }

    @Test
    void chainVerified_brokenResult_recordsTheBreakAndTheSignatureGaps() {
        ChainVerificationResult result = new ChainVerificationResult(ChainVerdict.BROKEN, 4, 1,
                5L, ChainBreakKind.SIGNATURE_MISMATCH, new ChainHead(4, "h".repeat(64)),
                null, new SignatureReport(2, 0, 2, List.of("9f2a1c4e8b7d0355"),
                        Map.of("0123456789abcdef", 1L)));

        Map<String, Object> payload =
                LedgerAuditEvents.chainVerified(tenantId, actorId, result).payload();

        assertThat(payload).contains(
                entry("verdict", "BROKEN"),
                entry("broken_at_sequence", 5L),
                entry("break_kind", "SIGNATURE_MISMATCH"),
                entry("missing_key_ids", List.of("9f2a1c4e8b7d0355")),
                entry("compromised_key_ids", List.of("0123456789abcdef")));
    }

    @Test
    void chainVerified_emptyChain_recordsNoHead() {
        Map<String, Object> payload = LedgerAuditEvents.chainVerified(tenantId, actorId,
                ChainVerificationResult.valid(0, 0)).payload();

        assertThat(payload).doesNotContainKeys("head_sequence", "head_entry_hash");
        assertThat(payload).contains(entry("chained_count", 0L));
    }

    @Test
    void chainVerified_payloadNeverContainsComplianceVocabulary() {
        String rendered = LedgerAuditEvents.chainVerified(tenantId, actorId,
                ChainVerificationResult.valid(3, 0)).payload().toString().toLowerCase(Locale.ROOT);

        for (String term : FORBIDDEN) {
            assertThat(rendered).as("payload must not contain '%s'", term).doesNotContain(term);
        }
    }

    @Test
    void chainVerified_requiresAllArguments() {
        ChainVerificationResult result = ChainVerificationResult.valid(0, 0);

        assertThatThrownBy(() -> LedgerAuditEvents.chainVerified(null, actorId, result))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LedgerAuditEvents.chainVerified(tenantId, null, result))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LedgerAuditEvents.chainVerified(tenantId, actorId, null))
                .isInstanceOf(NullPointerException.class);
    }
}
