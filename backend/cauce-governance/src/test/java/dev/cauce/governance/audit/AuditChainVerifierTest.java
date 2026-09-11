package dev.cauce.governance.audit;

import dev.cauce.core.audit.AuditEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.cauce.governance.persistence.AuditLogEntryEntity;
import dev.cauce.governance.persistence.AuditLogEntryMapper;
import dev.cauce.governance.persistence.AuditLogEntryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AuditChainVerifierTest {

    private final AuditLogEntryRepository logRepository =
            Mockito.mock(AuditLogEntryRepository.class);
    private final AuditLogEntryMapper logMapper = new AuditLogEntryMapper();
    private final AuditChainHasher hasher = new AuditChainHasher();
    private final AuditChainVerifier verifier =
            new AuditChainVerifier(logRepository, logMapper, hasher);

    private final UUID tenantId = UUID.randomUUID();

    /**
     * A correctly chained ledger of {@code n} entries, sequences {@code from..from+n-1}, each
     * written under {@code scheme} — which is how a mixed chain is built from two calls.
     */
    private List<AuditLogEntryEntity> chainOf(int n, long from, String initialPrev,
                                              String scheme) {
        List<AuditLogEntryEntity> entities = new ArrayList<>();
        String prev = initialPrev;
        for (long seq = from; seq < from + n; seq++) {
            AuditOutboxEntry outbox = AuditOutboxEntry.create(
                    new AuditEvent(tenantId, "e." + seq, Map.of("n", seq)));
            UUID id = AuditLogEntry.mintId();
            Instant drainedAt = AuditLogEntry.mintDrainedAt();
            String payloadHash = hasher.payloadHash(scheme, outbox.payload());
            String entryHash = hasher.entryHash(scheme, id, tenantId, seq, outbox.id(),
                    outbox.eventType(), drainedAt, payloadHash, prev);
            entities.add(logMapper.toEntity(AuditLogEntry.chained(id, outbox, seq, drainedAt,
                    payloadHash, prev, entryHash, scheme)));
            prev = entryHash;
        }
        return entities;
    }

    private List<AuditLogEntryEntity> chainOf(int n, long from, String initialPrev) {
        return chainOf(n, from, initialPrev, AuditChainHasher.CURRENT_SCHEME);
    }

    private List<AuditLogEntryEntity> chainOf(int n) {
        return chainOf(n, 1, hasher.genesisHash(tenantId));
    }

    /** The last entry hash of a chain, i.e. what the next segment must link to. */
    private static String headHashOf(List<AuditLogEntryEntity> chain) {
        return chain.get(chain.size() - 1).getEntryHash();
    }

    /** {@code entity} with its payload replaced (hashes untouched) — the tamper case. */
    private AuditLogEntryEntity withPayload(AuditLogEntryEntity entity,
                                            Map<String, Object> payload) {
        return new AuditLogEntryEntity(entity.getId(), entity.getTenantId(),
                entity.getSequenceNumber(), entity.getOutboxId(), entity.getEventType(), payload,
                entity.getDrainedAt(), entity.getPayloadHash(), entity.getPrevHash(),
                entity.getEntryHash(), entity.getHashScheme(), entity.getSignature());
    }

    private void stubLedger(List<AuditLogEntryEntity> entities) {
        when(logRepository.findByTenantIdOrderBySequenceNumberAsc(tenantId))
                .thenReturn(entities);
    }

    @Test
    void verifyChain_intactChain_isValid() {
        stubLedger(chainOf(4));

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isTrue();
        assertThat(result.chainedCount()).isEqualTo(4);
        assertThat(result.preChainCount()).isZero();
    }

    // === SCHEME COEXISTENCE: v1 is history, v2 is current, and a chain may hold both ===

    /** The existing instances: every entry written before this unit. Must still be VALID. */
    @Test
    void verifyChain_allV1Chain_isValid() {
        stubLedger(chainOf(4, 1, hasher.genesisHash(tenantId), AuditChainHasher.SCHEME_V1));

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isTrue();
        assertThat(result.chainedCount()).isEqualTo(4);
    }

    /** A ledger that started after this unit. */
    @Test
    void verifyChain_allV2Chain_isValid() {
        stubLedger(chainOf(4, 1, hasher.genesisHash(tenantId), AuditChainHasher.SCHEME_V2));

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isTrue();
        assertThat(result.chainedCount()).isEqualTo(4);
    }

    /**
     * The case this unit exists for: an instance upgraded mid-chain. The v1 prefix keeps
     * verifying under v1 rules, the v2 suffix links to it through the ordinary prev_hash, and
     * the whole chain verifies end to end without a single entry being re-hashed.
     */
    @Test
    void verifyChain_mixedV1ThenV2Chain_isValid() {
        List<AuditLogEntryEntity> v1Part =
                chainOf(3, 1, hasher.genesisHash(tenantId), AuditChainHasher.SCHEME_V1);
        List<AuditLogEntryEntity> v2Part =
                chainOf(2, 4, headHashOf(v1Part), AuditChainHasher.SCHEME_V2);
        List<AuditLogEntryEntity> mixed = new ArrayList<>(v1Part);
        mixed.addAll(v2Part);
        stubLedger(mixed);

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isTrue();
        assertThat(result.chainedCount()).isEqualTo(5);
    }

    /** Tampering is still caught on both sides of the scheme boundary, at the exact sequence. */
    @Test
    void verifyChain_mixedChainWithTamperedV2Entry_breaksAtThatExactSequence() {
        List<AuditLogEntryEntity> v1Part =
                chainOf(3, 1, hasher.genesisHash(tenantId), AuditChainHasher.SCHEME_V1);
        List<AuditLogEntryEntity> v2Part =
                chainOf(2, 4, headHashOf(v1Part), AuditChainHasher.SCHEME_V2);
        List<AuditLogEntryEntity> mixed = new ArrayList<>(v1Part);
        mixed.addAll(v2Part);
        mixed.set(3, withPayload(mixed.get(3), Map.of("tampered", true)));
        stubLedger(mixed);

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(4);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.PAYLOAD_HASH_MISMATCH);
    }

    @Test
    void verifyChain_mixedChainWithTamperedV1Entry_breaksAtThatExactSequence() {
        List<AuditLogEntryEntity> v1Part =
                chainOf(3, 1, hasher.genesisHash(tenantId), AuditChainHasher.SCHEME_V1);
        List<AuditLogEntryEntity> v2Part =
                chainOf(2, 4, headHashOf(v1Part), AuditChainHasher.SCHEME_V2);
        List<AuditLogEntryEntity> mixed = new ArrayList<>(v1Part);
        mixed.addAll(v2Part);
        mixed.set(1, withPayload(mixed.get(1), Map.of("tampered", true)));
        stubLedger(mixed);

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(2);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.PAYLOAD_HASH_MISMATCH);
    }

    /**
     * A v2 entry whose id was altered. Under v1 this was undetectable — the id was the one
     * column outside the preimage — which is the defect v2 closes.
     */
    @Test
    void verifyChain_v2EntryWithAlteredId_breaksAsEntryHashMismatch() {
        List<AuditLogEntryEntity> entities = new ArrayList<>(
                chainOf(2, 1, hasher.genesisHash(tenantId), AuditChainHasher.SCHEME_V2));
        AuditLogEntryEntity target = entities.get(1);
        entities.set(1, new AuditLogEntryEntity(UUID.randomUUID(), target.getTenantId(),
                target.getSequenceNumber(), target.getOutboxId(), target.getEventType(),
                target.getPayload(), target.getDrainedAt(), target.getPayloadHash(),
                target.getPrevHash(), target.getEntryHash(), target.getHashScheme(),
                target.getSignature()));
        stubLedger(entities);

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(2);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.ENTRY_HASH_MISMATCH);
    }

    @Test
    void verifyChain_emptyLedger_isTriviallyValid() {
        stubLedger(List.of());

        assertThat(verifier.verifyChain(tenantId))
                .isEqualTo(ChainVerificationResult.valid(0, 0));
    }

    @Test
    void verifyChain_tamperedPayload_breaksAtThatExactSequence() {
        List<AuditLogEntryEntity> entities = new ArrayList<>(chainOf(5));
        entities.set(2, withPayload(entities.get(2), Map.of("tampered", true)));
        stubLedger(entities);

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(3);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.PAYLOAD_HASH_MISMATCH);
        assertThat(result.chainedCount()).isEqualTo(2); // entries before the break verified
    }

    @Test
    void verifyChain_redactedPayload_stillVerifiesThroughTheStoredPayloadHash() {
        List<AuditLogEntryEntity> entities = new ArrayList<>(chainOf(3));
        entities.set(1, withPayload(entities.get(1), null)); // owner redaction

        stubLedger(entities);

        assertThat(verifier.verifyChain(tenantId).valid()).isTrue();
    }

    @Test
    void verifyChain_missingRow_breaksAsSequenceGap() {
        List<AuditLogEntryEntity> entities = new ArrayList<>(chainOf(4));
        entities.remove(1); // sequence 2 deleted by a privileged attacker
        stubLedger(entities);

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(3);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.SEQUENCE_GAP);
    }

    @Test
    void verifyChain_brokenLink_breaksAsPrevHashMismatch() {
        // Two internally consistent segments that do not link: the second starts from a
        // foreign prev instead of the first segment's last entry hash.
        List<AuditLogEntryEntity> entities = new ArrayList<>(chainOf(2));
        entities.addAll(chainOf(2, 3, "f".repeat(64)));
        stubLedger(entities);

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(3);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.PREV_HASH_MISMATCH);
    }

    @Test
    void verifyChain_tamperedMetadataField_breaksAsEntryHashMismatch() {
        List<AuditLogEntryEntity> entities = new ArrayList<>(chainOf(3));
        AuditLogEntryEntity target = entities.get(1);
        entities.set(1, new AuditLogEntryEntity(target.getId(), target.getTenantId(),
                target.getSequenceNumber(), target.getOutboxId(), "tampered.event",
                target.getPayload(), target.getDrainedAt(), target.getPayloadHash(),
                target.getPrevHash(), target.getEntryHash(), target.getHashScheme(),
                target.getSignature()));
        stubLedger(entities);

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(2);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.ENTRY_HASH_MISMATCH);
    }

    @Test
    void verifyChain_preChainRowsAsPrefix_areLegalAndCounted() {
        List<AuditLogEntryEntity> entities = new ArrayList<>();
        entities.add(preChainEntity(1));
        entities.add(preChainEntity(2));
        entities.addAll(chainOf(2, 3, hasher.genesisHash(tenantId)));
        stubLedger(entities);

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isTrue();
        assertThat(result.preChainCount()).isEqualTo(2);
        assertThat(result.chainedCount()).isEqualTo(2);
    }

    @Test
    void verifyChain_preChainRowAfterChainedRows_breaks() {
        List<AuditLogEntryEntity> entities = new ArrayList<>(chainOf(2));
        entities.add(preChainEntity(3));
        stubLedger(entities);

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(3);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.PRE_CHAIN_AFTER_CHAINED);
    }

    @Test
    void verifyChain_chainedRowWithUnknownScheme_breaksAsMalformed() {
        List<AuditLogEntryEntity> entities = new ArrayList<>(chainOf(2));
        AuditLogEntryEntity target = entities.get(1);
        entities.set(1, new AuditLogEntryEntity(target.getId(), target.getTenantId(),
                target.getSequenceNumber(), target.getOutboxId(), target.getEventType(),
                target.getPayload(), target.getDrainedAt(), target.getPayloadHash(),
                target.getPrevHash(), target.getEntryHash(), "v999", target.getSignature()));
        stubLedger(entities);

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(2);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.MALFORMED_ENTRY);
    }

    private AuditLogEntryEntity preChainEntity(long sequence) {
        return new AuditLogEntryEntity(UUID.randomUUID(), tenantId, sequence, UUID.randomUUID(),
                "pre.chain", Map.of("k", "v"), Instant.now(), null, null, null, null, null);
    }
}
