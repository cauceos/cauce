package dev.cauce.governance.audit;

import dev.cauce.core.audit.AuditEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.when;

import dev.cauce.governance.audit.signing.AuditEntrySigner;
import dev.cauce.governance.audit.signing.AuditKeyId;
import dev.cauce.governance.audit.signing.AuditKeyRegistry;
import dev.cauce.governance.audit.signing.AuditSignatureVerifier;
import dev.cauce.governance.audit.signing.AuditSigningKey;
import dev.cauce.governance.persistence.AuditLogEntryEntity;
import dev.cauce.governance.persistence.AuditLogEntryMapper;
import dev.cauce.governance.persistence.AuditLogEntryRepository;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
    /** The default: no registry, so unsigned chains are the baseline most tests assert on. */
    private final AuditChainVerifier verifier = verifierKnowing(AuditKeyRegistry.empty());

    private AuditChainVerifier verifierKnowing(AuditKeyRegistry registry) {
        return new AuditChainVerifier(logRepository, logMapper, hasher,
                new AuditSignatureVerifier(registry));
    }

    private final UUID tenantId = UUID.randomUUID();

    /**
     * A correctly chained ledger of {@code n} entries, sequences {@code from..from+n-1}, each
     * written under {@code scheme} — which is how a mixed chain is built from two calls.
     */
    private List<AuditLogEntryEntity> chainOf(int n, long from, String initialPrev,
                                              String scheme) {
        return chainOf(n, from, initialPrev, scheme, null);
    }

    /** The same, signed by {@code signer} when one is given. */
    private List<AuditLogEntryEntity> chainOf(int n, long from, String initialPrev, String scheme,
                                              AuditEntrySigner signer) {
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
                    payloadHash, prev, entryHash, scheme,
                    signer == null ? null : signer.sign(entryHash),
                    signer == null ? null : signer.keyId(),
                    signer == null ? null : signer.signatureScheme())));
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
                entity.getEntryHash(), entity.getHashScheme(), entity.getSignature(),
                entity.getKeyId(), entity.getSignatureScheme());
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

    // === SIGNATURES: coverage is reported, and only a WRONG signature is a break ===

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AuditEntrySigner signerFor(KeyPair pair) {
        return new AuditEntrySigner(pair.getPrivate(), AuditKeyId.of(pair.getPublic()));
    }

    private static AuditSigningKey publicKeyOf(KeyPair pair, Instant compromisedAt) {
        return new AuditSigningKey(AuditKeyId.of(pair.getPublic()), pair.getPublic(), null, null,
                compromisedAt);
    }

    @Test
    void verifyChain_signedChainWithKnownKey_isValidAndFullyVerified() {
        KeyPair pair = newKeyPair();
        stubLedger(chainOf(3, 1, hasher.genesisHash(tenantId), AuditChainHasher.SCHEME_V2,
                signerFor(pair)));

        ChainVerificationResult result =
                verifierKnowing(AuditKeyRegistry.of(publicKeyOf(pair, null))).verifyChain(tenantId);

        assertThat(result.valid()).isTrue();
        assertThat(result.signatures().verified()).isEqualTo(3);
        assertThat(result.signatures().unsigned()).isZero();
        assertThat(result.signatures().unverifiable()).isZero();
        assertThat(result.signatures().fullyVerified()).isTrue();
    }

    /** A signature that is present, checkable and WRONG. This one IS a break. */
    @Test
    void verifyChain_tamperedSignature_breaksAtThatExactSequence() {
        KeyPair pair = newKeyPair();
        List<AuditLogEntryEntity> entities = new ArrayList<>(chainOf(3, 1,
                hasher.genesisHash(tenantId), AuditChainHasher.SCHEME_V2, signerFor(pair)));
        AuditLogEntryEntity target = entities.get(1);
        byte[] raw = Base64.getDecoder().decode(target.getSignature());
        raw[0] ^= 0x01;
        entities.set(1, withSignature(target, Base64.getEncoder().encodeToString(raw)));
        stubLedger(entities);

        ChainVerificationResult result =
                verifierKnowing(AuditKeyRegistry.of(publicKeyOf(pair, null))).verifyChain(tenantId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(2);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.SIGNATURE_MISMATCH);
    }

    /**
     * The attack recomputation alone cannot catch: a privileged actor alters an entry and
     * recomputes BOTH hashes correctly. The signature still covers the hash of what was
     * actually written, and they cannot produce a new one without the private key.
     */
    @Test
    void verifyChain_entryRehashedWithoutTheSigningKey_breaksOnTheSignature() {
        KeyPair pair = newKeyPair();
        List<AuditLogEntryEntity> entities = new ArrayList<>(chainOf(2, 1,
                hasher.genesisHash(tenantId), AuditChainHasher.SCHEME_V2, signerFor(pair)));
        AuditLogEntryEntity target = entities.get(1);
        Map<String, Object> forged = Map.of("forged", true);
        String payloadHash = hasher.payloadHash(AuditChainHasher.SCHEME_V2, forged);
        String entryHash = hasher.entryHash(AuditChainHasher.SCHEME_V2, target.getId(), tenantId,
                target.getSequenceNumber(), target.getOutboxId(), target.getEventType(),
                target.getDrainedAt(), payloadHash, target.getPrevHash());
        entities.set(1, new AuditLogEntryEntity(target.getId(), tenantId,
                target.getSequenceNumber(), target.getOutboxId(), target.getEventType(), forged,
                target.getDrainedAt(), payloadHash, target.getPrevHash(), entryHash,
                target.getHashScheme(), target.getSignature(), target.getKeyId(),
                target.getSignatureScheme()));
        stubLedger(entities);

        ChainVerificationResult result =
                verifierKnowing(AuditKeyRegistry.of(publicKeyOf(pair, null))).verifyChain(tenantId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(2);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.SIGNATURE_MISMATCH);
    }

    /**
     * The public key for a key_id in the chain is missing. The chain recomputes, so the
     * verdict stays valid; the signatures are reported as unverifiable, naming the key_id.
     * Neither "broken" nor "verified" — both would be lies.
     */
    @Test
    void verifyChain_signedButNoPublicKeyAvailable_isValidAndReportedUnverifiable() {
        KeyPair pair = newKeyPair();
        stubLedger(chainOf(2, 1, hasher.genesisHash(tenantId), AuditChainHasher.SCHEME_V2,
                signerFor(pair)));

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isTrue();
        assertThat(result.signatures().verified()).isZero();
        assertThat(result.signatures().unverifiable()).isEqualTo(2);
        assertThat(result.signatures().missingKeyIds())
                .containsExactly(AuditKeyId.of(pair.getPublic()));
        assertThat(result.signatures().fullyVerified()).isFalse();
    }

    /** An unsigned entry is never a failure: every v1 entry is one, by construction. */
    @Test
    void verifyChain_unsignedChain_isValidAndReportedUnsigned() {
        stubLedger(chainOf(3, 1, hasher.genesisHash(tenantId), AuditChainHasher.SCHEME_V1));

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isTrue();
        assertThat(result.signatures().unsigned()).isEqualTo(3);
        assertThat(result.signatures().fullyVerified()).isFalse();
    }

    /** The upgrade shape: unsigned v1 history, then signed v2 entries on the same chain. */
    @Test
    void verifyChain_unsignedV1PrefixThenSignedV2Suffix_isValidAndReportsBoth() {
        KeyPair pair = newKeyPair();
        List<AuditLogEntryEntity> unsigned =
                chainOf(2, 1, hasher.genesisHash(tenantId), AuditChainHasher.SCHEME_V1);
        List<AuditLogEntryEntity> signed = chainOf(3, 3, headHashOf(unsigned),
                AuditChainHasher.SCHEME_V2, signerFor(pair));
        List<AuditLogEntryEntity> mixed = new ArrayList<>(unsigned);
        mixed.addAll(signed);
        stubLedger(mixed);

        ChainVerificationResult result =
                verifierKnowing(AuditKeyRegistry.of(publicKeyOf(pair, null))).verifyChain(tenantId);

        assertThat(result.valid()).isTrue();
        assertThat(result.chainedCount()).isEqualTo(5);
        assertThat(result.signatures().unsigned()).isEqualTo(2);
        assertThat(result.signatures().verified()).isEqualTo(3);
    }

    /**
     * Rotation: two key_ids in one chain. Entries signed by the retired key keep verifying
     * against its public half, which is why public keys are never removed from the registry.
     */
    @Test
    void verifyChain_twoKeyIdsInOneChain_bothVerify() {
        KeyPair oldPair = newKeyPair();
        KeyPair newPair = newKeyPair();
        List<AuditLogEntryEntity> before = chainOf(2, 1, hasher.genesisHash(tenantId),
                AuditChainHasher.SCHEME_V2, signerFor(oldPair));
        List<AuditLogEntryEntity> after = chainOf(2, 3, headHashOf(before),
                AuditChainHasher.SCHEME_V2, signerFor(newPair));
        List<AuditLogEntryEntity> rotated = new ArrayList<>(before);
        rotated.addAll(after);
        stubLedger(rotated);

        ChainVerificationResult result = verifierKnowing(AuditKeyRegistry.of(
                publicKeyOf(oldPair, null), publicKeyOf(newPair, null))).verifyChain(tenantId);

        assertThat(result.valid()).isTrue();
        assertThat(result.signatures().verified()).isEqualTo(4);
        assertThat(rotated.stream().map(AuditLogEntryEntity::getKeyId).distinct().toList())
                .hasSize(2);
    }

    /** Losing only the RETIRED key's public half: the old entries become unverifiable. */
    @Test
    void verifyChain_rotatedChainMissingTheRetiredPublicKey_reportsOnlyThatGap() {
        KeyPair oldPair = newKeyPair();
        KeyPair newPair = newKeyPair();
        List<AuditLogEntryEntity> before = chainOf(2, 1, hasher.genesisHash(tenantId),
                AuditChainHasher.SCHEME_V2, signerFor(oldPair));
        List<AuditLogEntryEntity> after = chainOf(3, 3, headHashOf(before),
                AuditChainHasher.SCHEME_V2, signerFor(newPair));
        List<AuditLogEntryEntity> rotated = new ArrayList<>(before);
        rotated.addAll(after);
        stubLedger(rotated);

        ChainVerificationResult result =
                verifierKnowing(AuditKeyRegistry.of(publicKeyOf(newPair, null)))
                        .verifyChain(tenantId);

        assertThat(result.valid()).isTrue();
        assertThat(result.signatures().verified()).isEqualTo(3);
        assertThat(result.signatures().unverifiable()).isEqualTo(2);
        assertThat(result.signatures().missingKeyIds())
                .containsExactly(AuditKeyId.of(oldPair.getPublic()));
    }

    /** A compromised key is REPORTED, with the entries it signed. It does not fail anything. */
    @Test
    void verifyChain_compromisedKey_isValidAndReported() {
        KeyPair pair = newKeyPair();
        stubLedger(chainOf(2, 1, hasher.genesisHash(tenantId), AuditChainHasher.SCHEME_V2,
                signerFor(pair)));

        ChainVerificationResult result = verifierKnowing(AuditKeyRegistry.of(
                publicKeyOf(pair, Instant.parse("2026-06-01T00:00:00Z")))).verifyChain(tenantId);

        assertThat(result.valid()).isTrue();
        assertThat(result.signatures().verified()).isEqualTo(2);
        assertThat(result.signatures().compromisedKeyIds())
                .containsExactly(entry(AuditKeyId.of(pair.getPublic()), 2L));
    }

    /** {@code entity} with its signature replaced, everything else untouched. */
    private AuditLogEntryEntity withSignature(AuditLogEntryEntity entity, String signature) {
        return new AuditLogEntryEntity(entity.getId(), entity.getTenantId(),
                entity.getSequenceNumber(), entity.getOutboxId(), entity.getEventType(),
                entity.getPayload(), entity.getDrainedAt(), entity.getPayloadHash(),
                entity.getPrevHash(), entity.getEntryHash(), entity.getHashScheme(), signature,
                entity.getKeyId(), entity.getSignatureScheme());
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
                target.getSignature(), target.getKeyId(), target.getSignatureScheme()));
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
                target.getSignature(), target.getKeyId(), target.getSignatureScheme()));
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
                target.getPrevHash(), target.getEntryHash(), "v999", target.getSignature(),
                target.getKeyId(), target.getSignatureScheme()));
        stubLedger(entities);

        ChainVerificationResult result = verifier.verifyChain(tenantId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(2);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.MALFORMED_ENTRY);
    }

    private AuditLogEntryEntity preChainEntity(long sequence) {
        return new AuditLogEntryEntity(UUID.randomUUID(), tenantId, sequence, UUID.randomUUID(),
                "pre.chain", Map.of("k", "v"), Instant.now(), null, null, null, null, null,
                null, null);
    }
}
