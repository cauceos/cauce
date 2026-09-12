package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.governance.audit.signing.AuditKeyId;
import dev.cauce.governance.audit.signing.AuditKeyRegistry;
import dev.cauce.governance.audit.signing.AuditSignatureCheck;
import dev.cauce.governance.audit.signing.AuditSignatureVerifier;
import dev.cauce.governance.audit.signing.AuditSigningKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins every test vector published in {@code docs/spec/audit-chain-format.md}.
 *
 * <p>The specification exists so that a third party can write a verifier without reading this
 * code. That promise rots the moment the two drift, and a document cannot notice that it has
 * been contradicted — so this test makes the drift a build failure instead.
 *
 * <p>Two things are asserted per vector, and the second is the one that matters: that the
 * hasher produces the published hash, AND that the published PREIMAGE STRING — the exact
 * bytes the document tells an implementer to build — hashes to it. The first alone would let
 * the document describe a preimage nobody actually computes.
 *
 * <p><b>If one of these fails, fix the code or amend the document — never the literal.</b>
 * Editing a literal to match new behaviour silently republishes a format that existing
 * entries were not written under.
 */
class AuditFormatSpecVectorsTest {

    // §10.2 — the worked example, identical across both schemes.
    private static final UUID ID = UUID.fromString("00000000-0000-7000-8000-0000000000e3");
    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID OUTBOX = UUID.fromString("00000000-0000-7000-8000-0000000000a2");
    private static final long SEQUENCE = 5;
    private static final String EVENT_TYPE = "conduct.message.received";
    private static final Instant DRAINED_AT = Instant.parse("2026-09-11T10:00:00Z");
    private static final String PAYLOAD_HASH = "p".repeat(64);
    private static final String PREV_HASH = "q".repeat(64);

    private static final String V1_PREIMAGE =
            "{\"drained_at\":\"2026-09-11T10:00:00Z\","
                    + "\"event_type\":\"conduct.message.received\","
                    + "\"outbox_id\":\"00000000-0000-7000-8000-0000000000a2\","
                    + "\"payload_hash\":\"" + PAYLOAD_HASH + "\","
                    + "\"prev_hash\":\"" + PREV_HASH + "\","
                    + "\"scheme\":\"v1\","
                    + "\"sequence_number\":5,"
                    + "\"tenant_id\":\"00000000-0000-7000-8000-000000000001\"}";
    private static final String V1_ENTRY_HASH =
            "417b2ecfa75612c53dca948f544f7309da10bf1031646485473d6e00a6054875";

    private static final String V2_PREIMAGE =
            "{\"drained_at\":\"2026-09-11T10:00:00.000000Z\","
                    + "\"event_type\":\"conduct.message.received\","
                    + "\"id\":\"00000000-0000-7000-8000-0000000000e3\","
                    + "\"outbox_id\":\"00000000-0000-7000-8000-0000000000a2\","
                    + "\"payload_hash\":\"" + PAYLOAD_HASH + "\","
                    + "\"prev_hash\":\"" + PREV_HASH + "\","
                    + "\"scheme\":\"v2\","
                    + "\"sequence_number\":5,"
                    + "\"tenant_id\":\"00000000-0000-7000-8000-000000000001\"}";
    private static final String V2_ENTRY_HASH =
            "c532e13eccdd3d9c2f727840b0340c253e91939329e64319aad9443546743a89";

    // §10.1
    private static final String GENESIS_PREIMAGE =
            "cauce-audit-genesis:v1:00000000-0000-7000-8000-000000000001";
    private static final String GENESIS_HASH =
            "0aaeefb6e95ffe2c9f1991f9964cbe1b7b4c200826d6d5c4149284a9be767fe5";

    // §10.5 — a key generated for the document, used by no instance; only the public half.
    private static final String SPEC_KEY_ID = "526b1a687e2f4293";
    private static final String SPEC_PUBLIC_KEY =
            "MCowBQYDK2VwAyEAvW00gfFNBbKDKfKbAky7KUqJZg2PjbnHru+jG0xBwWE=";
    private static final String SPEC_SIGNATURE =
            "rU8G/ugMySdy/2PaiWtZMSmK2Dzzf7fgG5UbM1Ph2YySyCTO4DNdq5wSgC5xhQebxFLGGnSiTHdr"
                    + "/1V93r3vAA==";
    private static final String SPEC_SIGNATURE_SCHEME = "ed25519-v1";

    private final AuditChainHasher hasher = new AuditChainHasher();

    /** SHA-256, hex — the outer step every vector in the document ends with. */
    private static String sha256Hex(String input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    // === §10.1 genesis ===

    @Test
    void genesis_matchesTheDocumentedPreimageAndHash() throws Exception {
        assertThat(sha256Hex(GENESIS_PREIMAGE)).isEqualTo(GENESIS_HASH);
        assertThat(hasher.genesisHash(TENANT)).isEqualTo(GENESIS_HASH);
    }

    // === §10.2 entry hash, both schemes ===

    @Test
    void entryHashV1_matchesTheDocumentedPreimageAndHash() throws Exception {
        assertThat(sha256Hex(V1_PREIMAGE)).isEqualTo(V1_ENTRY_HASH);
        assertThat(hasher.entryHash(AuditChainHasher.SCHEME_V1, ID, TENANT, SEQUENCE, OUTBOX,
                EVENT_TYPE, DRAINED_AT, PAYLOAD_HASH, PREV_HASH)).isEqualTo(V1_ENTRY_HASH);
    }

    @Test
    void entryHashV2_matchesTheDocumentedPreimageAndHash() throws Exception {
        assertThat(sha256Hex(V2_PREIMAGE)).isEqualTo(V2_ENTRY_HASH);
        assertThat(hasher.entryHash(AuditChainHasher.SCHEME_V2, ID, TENANT, SEQUENCE, OUTBOX,
                EVENT_TYPE, DRAINED_AT, PAYLOAD_HASH, PREV_HASH)).isEqualTo(V2_ENTRY_HASH);
    }

    /** §5.3: the two preimages differ in exactly three places, and the document says which. */
    @Test
    void theTwoPreimages_differOnlyInTimestampIdAndScheme() {
        assertThat(V1_PREIMAGE).contains("\"2026-09-11T10:00:00Z\"").doesNotContain("\"id\":")
                .contains("\"scheme\":\"v1\"");
        assertThat(V2_PREIMAGE).contains("\"2026-09-11T10:00:00.000000Z\"")
                .contains("\"id\":\"00000000-0000-7000-8000-0000000000e3\"")
                .contains("\"scheme\":\"v2\"");
    }

    // === §10.3 payload hash ===

    @Test
    void payloadHash_asciiDocument_agreesAcrossSchemes() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content_hash", "3f2b1c");
        payload.put("rounds", 2);
        payload.put("finish_reason", "STOP");
        String documented = "6b9695c971ed3d84721505400296857d0ca8cbb7eee671f38039fc459a73a57b";

        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V1, payload)).isEqualTo(documented);
        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V2, payload)).isEqualTo(documented);
    }

    /** The NFC/NFD pair. Written as escapes: the two forms render identically. */
    @Test
    void payloadHash_unicodeForms_divergeUnderV1AndAgreeUnderV2() {
        Map<String, Object> nfd = Map.of("actor", "josé");
        Map<String, Object> nfc = Map.of("actor", "josé");
        String nfdV1 = "68e3c767fabfd41456e91b48f91429369453d510ebfa7a153adaa6fe5063c566";
        String normalised = "d02fa0fb31ba5fa261947ee7c604b9487ef3aa85778d4fd823c32f5894892a8a";

        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V1, nfd)).isEqualTo(nfdV1);
        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V1, nfc)).isEqualTo(normalised);
        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V2, nfd)).isEqualTo(normalised);
        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V2, nfc)).isEqualTo(normalised);
    }

    // === §10.4 encoding corner cases ===

    @Test
    void encodingCornerCases_matchTheDocumentedCanonicalDocuments() throws Exception {
        Map<String, Object> nullValue = new LinkedHashMap<>();
        nullValue.put("n", null);
        Map<String, Object> emptyString = new LinkedHashMap<>();
        emptyString.put("n", "");

        String one = "2bfd14f43d17fc7cea24e0917a8879b4b2f880b8baeec1b9d90fbaad655e71bd";
        assertThat(sha256Hex("{\"n\":1}")).isEqualTo(one);
        // 1 and 1.0 collide by construction (§4.5).
        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V2, Map.of("n", 1))).isEqualTo(one);
        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V2, Map.of("n", 1.0))).isEqualTo(one);

        String nullHash = "5b4da02351c1c20974b216a5e3a4edb59ac51cbda6be3e9d82952b4f5beea463";
        assertThat(sha256Hex("{\"n\":null}")).isEqualTo(nullHash);
        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V2, nullValue)).isEqualTo(nullHash);

        String emptyHash = "6b760ea9d4ac65941818f2a3a9afc102217be814b3a4684c685f38be1054a3d2";
        assertThat(sha256Hex("{\"n\":\"\"}")).isEqualTo(emptyHash);
        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V2, emptyString))
                .isEqualTo(emptyHash);
        // null and "" are NOT the same document.
        assertThat(emptyHash).isNotEqualTo(nullHash);

        String emptyObject = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";
        assertThat(sha256Hex("{}")).isEqualTo(emptyObject);
        assertThat(hasher.payloadHash(AuditChainHasher.SCHEME_V2, Map.of()))
                .isEqualTo(emptyObject);
    }

    // === §10.5 signature ===

    @Test
    void signatureVector_verifiesAgainstThePublishedPublicKey() throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(SPEC_PUBLIC_KEY)));
        AuditSignatureVerifier verifier = new AuditSignatureVerifier(AuditKeyRegistry.of(
                new AuditSigningKey(SPEC_KEY_ID, publicKey, null, null, null)));

        AuditSignatureCheck check = verifier.check(SPEC_SIGNATURE, SPEC_KEY_ID,
                SPEC_SIGNATURE_SCHEME, V2_ENTRY_HASH);

        assertThat(check.outcome()).isEqualTo(AuditSignatureCheck.Outcome.VERIFIED);
    }

    /** §8.5: the id is reproducible from the published key alone. */
    @Test
    void keyId_isDerivableFromThePublishedPublicKey() {
        assertThat(AuditKeyId.of(Base64.getDecoder().decode(SPEC_PUBLIC_KEY)))
                .isEqualTo(SPEC_KEY_ID);
    }

    /** §8.1: the signed bytes are exactly the string the document prints. */
    @Test
    void signedBytes_areTheDocumentedString() {
        String documented = "cauce-audit-signature:v1:" + SPEC_KEY_ID + ":"
                + SPEC_SIGNATURE_SCHEME + ":" + V2_ENTRY_HASH;

        assertThat(documented).isEqualTo("cauce-audit-signature:v1:526b1a687e2f4293:ed25519-v1:"
                + "c532e13eccdd3d9c2f727840b0340c253e91939329e64319aad9443546743a89");
    }
}
