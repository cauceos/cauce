package dev.cauce.governance.audit.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The signing primitives: key ids, the signed preimage, and the three verification outcomes. */
class AuditSigningTest {

    private static final String ENTRY_HASH = "a".repeat(64);

    private KeyPair keyPair;
    private KeyPair otherKeyPair;
    private AuditEntrySigner signer;
    private String keyId;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        keyPair = generator.generateKeyPair();
        otherKeyPair = generator.generateKeyPair();
        keyId = AuditKeyId.of(keyPair.getPublic());
        signer = new AuditEntrySigner(keyPair.getPrivate(), keyId);
    }

    private AuditSignatureVerifier verifierKnowing(AuditSigningKey... keys) {
        return new AuditSignatureVerifier(AuditKeyRegistry.of(keys));
    }

    private AuditSigningKey key(Instant compromisedAt) {
        return new AuditSigningKey(keyId, keyPair.getPublic(), Instant.parse("2026-01-01T00:00:00Z"),
                null, compromisedAt);
    }

    // === key ids ===

    @Test
    void keyId_isStableSixteenHexCharactersPerPublicKey() {
        assertThat(keyId).hasSize(AuditKeyId.LENGTH).matches("[0-9a-f]{16}")
                .isEqualTo(AuditKeyId.of(keyPair.getPublic()))
                .isNotEqualTo(AuditKeyId.of(otherKeyPair.getPublic()));
    }

    @Test
    void keyId_isDerivedFromTheEncodedPublicKeyAlone() {
        // What an independent implementation would have: the published base64 SPKI, nothing else.
        byte[] published = Base64.getDecoder().decode(
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));

        assertThat(AuditKeyId.of(published)).isEqualTo(keyId);
    }

    // === signing and verification ===

    @Test
    void sign_thenVerify_isVerified() {
        String signature = signer.sign(ENTRY_HASH);

        AuditSignatureCheck check = verifierKnowing(key(null))
                .check(signature, keyId, signer.signatureScheme(), ENTRY_HASH);

        assertThat(check.outcome()).isEqualTo(AuditSignatureCheck.Outcome.VERIFIED);
        assertThat(check.keyId()).isEqualTo(keyId);
        assertThat(check.compromisedKey()).isFalse();
    }

    @Test
    void sign_isDeterministic() {
        assertThat(signer.sign(ENTRY_HASH)).isEqualTo(signer.sign(ENTRY_HASH));
    }

    @Test
    void verify_signatureOverADifferentEntryHash_isInvalid() {
        String signature = signer.sign(ENTRY_HASH);

        AuditSignatureCheck check = verifierKnowing(key(null))
                .check(signature, keyId, signer.signatureScheme(), "b".repeat(64));

        assertThat(check.outcome()).isEqualTo(AuditSignatureCheck.Outcome.INVALID);
    }

    @Test
    void verify_signatureFromAnotherKey_isInvalid() {
        String foreign = new AuditEntrySigner(otherKeyPair.getPrivate(), keyId).sign(ENTRY_HASH);

        AuditSignatureCheck check = verifierKnowing(key(null))
                .check(foreign, keyId, signer.signatureScheme(), ENTRY_HASH);

        assertThat(check.outcome()).isEqualTo(AuditSignatureCheck.Outcome.INVALID);
    }

    @Test
    void verify_tamperedSignature_isInvalid() {
        byte[] raw = Base64.getDecoder().decode(signer.sign(ENTRY_HASH));
        raw[0] ^= 0x01;

        AuditSignatureCheck check = verifierKnowing(key(null)).check(
                Base64.getEncoder().encodeToString(raw), keyId, signer.signatureScheme(),
                ENTRY_HASH);

        assertThat(check.outcome()).isEqualTo(AuditSignatureCheck.Outcome.INVALID);
    }

    @Test
    void verify_signatureThatIsNotBase64_isInvalid() {
        AuditSignatureCheck check = verifierKnowing(key(null))
                .check("not base64 at all!", keyId, signer.signatureScheme(), ENTRY_HASH);

        assertThat(check.outcome()).isEqualTo(AuditSignatureCheck.Outcome.INVALID);
    }

    /**
     * Domain separation and key binding: the key_id is INSIDE what is signed, so a signature
     * cannot be relabelled onto a row claiming a different key.
     */
    @Test
    void verify_signatureRelabelledToAnotherKeyId_isInvalid() {
        String otherKeyId = AuditKeyId.of(otherKeyPair.getPublic());
        String signature = signer.sign(ENTRY_HASH);
        AuditSigningKey asOtherId =
                new AuditSigningKey(otherKeyId, keyPair.getPublic(), null, null, null);

        AuditSignatureCheck check = verifierKnowing(asOtherId)
                .check(signature, otherKeyId, signer.signatureScheme(), ENTRY_HASH);

        assertThat(check.outcome()).isEqualTo(AuditSignatureCheck.Outcome.INVALID);
    }

    // === the three non-failure outcomes ===

    @Test
    void verify_unsignedEntry_isUnsignedNotAFailure() {
        AuditSignatureCheck check = verifierKnowing(key(null)).check(null, null, null, ENTRY_HASH);

        assertThat(check.outcome()).isEqualTo(AuditSignatureCheck.Outcome.UNSIGNED);
    }

    /** PC1: a missing public key is an answer this instance cannot give, never a failure. */
    @Test
    void verify_missingPublicKey_isUnverifiableNotInvalid() {
        String signature = signer.sign(ENTRY_HASH);

        AuditSignatureCheck check = new AuditSignatureVerifier(AuditKeyRegistry.empty())
                .check(signature, keyId, signer.signatureScheme(), ENTRY_HASH);

        assertThat(check.outcome()).isEqualTo(AuditSignatureCheck.Outcome.UNVERIFIABLE);
        assertThat(check.reason()).isEqualTo(UnverifiableReason.MISSING_PUBLIC_KEY);
        assertThat(check.keyId()).isEqualTo(keyId);
    }

    @Test
    void verify_unknownSignatureScheme_isUnverifiableNotInvalid() {
        AuditSignatureCheck check = verifierKnowing(key(null))
                .check(signer.sign(ENTRY_HASH), keyId, "ed25519-v99", ENTRY_HASH);

        assertThat(check.outcome()).isEqualTo(AuditSignatureCheck.Outcome.UNVERIFIABLE);
        assertThat(check.reason()).isEqualTo(UnverifiableReason.UNKNOWN_SCHEME);
    }

    @Test
    void verify_partialSignatureColumns_isInvalid() {
        AuditSignatureCheck check = verifierKnowing(key(null))
                .check(signer.sign(ENTRY_HASH), null, signer.signatureScheme(), ENTRY_HASH);

        assertThat(check.outcome()).isEqualTo(AuditSignatureCheck.Outcome.INVALID);
    }

    // === compromise ===

    /** A compromised key REPORTS; it does not fail. The conclusion belongs to the auditor. */
    @Test
    void verify_compromisedKey_stillVerifiesAndIsFlagged() {
        AuditSignatureCheck check = verifierKnowing(key(Instant.parse("2026-06-01T00:00:00Z")))
                .check(signer.sign(ENTRY_HASH), keyId, signer.signatureScheme(), ENTRY_HASH);

        assertThat(check.outcome()).isEqualTo(AuditSignatureCheck.Outcome.VERIFIED);
        assertThat(check.compromisedKey()).isTrue();
    }

    // === signer guards ===

    @Test
    void signer_requiresAKeyAndAnId() {
        assertThatThrownBy(() -> new AuditEntrySigner(null, keyId))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuditEntrySigner(keyPair.getPrivate(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
