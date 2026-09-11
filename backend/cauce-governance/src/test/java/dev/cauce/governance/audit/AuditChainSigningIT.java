package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.governance.audit.signing.AuditEntrySigner;
import dev.cauce.governance.audit.signing.AuditKeyId;
import dev.cauce.governance.support.AbstractGovernanceIntegrationTest;
import dev.cauce.governance.support.AuditedBusinessFixture;
import dev.cauce.tenancy.TenantService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The signing layer end to end, against a real database and through the REAL application
 * beans — the drainer and verifier the application wires, not hand-built copies, so the
 * transaction boundary and the RLS context aspect are exercised as they are in production.
 *
 * <p>The instance under test is configured WITH a signing key, through the real properties,
 * which is why this lives in its own class: the unsigned configuration is the one
 * {@link AuditChainIT} asserts.
 *
 * <p>The test that matters is {@code privilegedRewrite}: a rewrite with correctly recomputed
 * hashes, which recomputation alone reads as a valid chain, caught by the signature the
 * attacker cannot produce because the private key is not in the database.
 */
class AuditChainSigningIT extends AbstractGovernanceIntegrationTest {

    /** Fixed for the JVM so the configured signer and the published registry agree. */
    private static final KeyPair SIGNING_PAIR = newKeyPair();
    private static final KeyPair PREVIOUS_PAIR = newKeyPair();

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String keyIdOf(KeyPair pair) {
        return AuditKeyId.of(pair.getPublic());
    }

    /**
     * Configures signing the way an operator does — through the real properties, so this IT
     * exercises the actual wiring: key parsing, the startup self-test of the pair, and loading
     * the published registry from a FILE. The registry publishes both public keys: the current
     * one and a previous, rotated-out one whose entries must keep verifying.
     */
    @DynamicPropertySource
    static void signingProperties(DynamicPropertyRegistry registry) throws IOException {
        Path registryFile = Files.createTempFile("cauce-audit-keys", ".json");
        registryFile.toFile().deleteOnExit();
        Files.writeString(registryFile, """
                { "registry_version": 1, "keys": [
                  { "key_id": "%s", "algorithm": "ed25519", "public_key": "%s",
                    "activated_at": "2026-01-01T00:00:00Z", "retired_at": null,
                    "compromised_at": null },
                  { "key_id": "%s", "algorithm": "ed25519", "public_key": "%s",
                    "activated_at": "2025-01-01T00:00:00Z",
                    "retired_at": "2026-01-01T00:00:00Z", "compromised_at": null } ] }
                """.formatted(
                        keyIdOf(SIGNING_PAIR), base64(SIGNING_PAIR.getPublic().getEncoded()),
                        keyIdOf(PREVIOUS_PAIR), base64(PREVIOUS_PAIR.getPublic().getEncoded())),
                StandardCharsets.UTF_8);

        registry.add("cauce.security.audit-signing.private-key",
                () -> base64(SIGNING_PAIR.getPrivate().getEncoded()));
        registry.add("cauce.security.audit-signing.public-key",
                () -> base64(SIGNING_PAIR.getPublic().getEncoded()));
        registry.add("cauce.security.audit-signing.registry-path",
                () -> registryFile.toAbsolutePath().toString());
    }

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AuditedBusinessFixture fixture;

    @Autowired
    private AuditOutboxDrainService drainService;

    @Autowired
    private AuditChainVerifier verifier;

    @Autowired
    private AuditChainHasher hasher;

    @Autowired
    private AuditDrainerProperties properties;

    private JdbcTemplate jdbc;
    private Tenant operator;
    private Tenant partner;
    private Tenant client;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(adminDataSource);
        truncateAll();
        TenantContext.clear();
        operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        client = tenantService.createClient("Client", partner.id());
        TenantContext.clear();
        jdbc.execute("TRUNCATE TABLE audit_chain_heads, audit_log_entries, audit_outbox");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** The real drainer, with a key configured, stamps all three signature columns. */
    @Test
    void drain_withSigningKeyConfigured_signsEveryEntryAndVerifies() {
        seedEvents("e.one", "e.two");
        drain();

        assertThat(jdbc.queryForList("SELECT key_id FROM audit_log_entries WHERE tenant_id = ?",
                String.class, client.id()))
                .containsOnly(keyIdOf(SIGNING_PAIR));
        assertThat(jdbc.queryForList(
                "SELECT signature_scheme FROM audit_log_entries WHERE tenant_id = ?",
                String.class, client.id()))
                .containsOnly(AuditEntrySigner.SIGNATURE_SCHEME);

        ChainVerificationResult result = verify();

        assertThat(result.valid()).isTrue();
        assertThat(result.chainedCount()).isEqualTo(2);
        assertThat(result.signatures().verified()).isEqualTo(2);
        assertThat(result.signatures().unverifiable()).isZero();
        assertThat(result.signatures().fullyVerified()).isTrue();
    }

    /**
     * The payoff of the whole layer. A privileged actor (the owner connection, which bypasses
     * the V21 append-only revoke) alters an entry and recomputes its hashes correctly — the
     * rewrite the documented residual says recomputation cannot detect. It IS detected here,
     * because producing a matching signature needs the private key, which is not in the
     * database.
     */
    @Test
    void privilegedRewriteWithCorrectHashes_isCaughtByTheSignature() {
        seedEvents("e.one", "e.two");
        drain();

        Map<String, Object> row = jdbc.queryForMap("SELECT id, outbox_id, event_type, "
                + "drained_at, prev_hash FROM audit_log_entries "
                + "WHERE tenant_id = ? AND sequence_number = 2", client.id());
        Map<String, Object> forged = Map.of("forged", true);
        String payloadHash = hasher.payloadHash(AuditChainHasher.SCHEME_V2, forged);
        String entryHash = hasher.entryHash(AuditChainHasher.SCHEME_V2, (UUID) row.get("id"),
                client.id(), 2, (UUID) row.get("outbox_id"), (String) row.get("event_type"),
                ((java.sql.Timestamp) row.get("drained_at")).toInstant(), payloadHash,
                (String) row.get("prev_hash"));
        jdbc.update("UPDATE audit_log_entries SET payload = ?::jsonb, payload_hash = ?, "
                        + "entry_hash = ? WHERE tenant_id = ? AND sequence_number = 2",
                "{\"forged\": true}", payloadHash, entryHash, client.id());

        ChainVerificationResult result = verify();

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(2);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.SIGNATURE_MISMATCH);
    }

    /** Stripping the signature outright does not help either: the hashes no longer match. */
    @Test
    void privilegedRewriteThatAlsoDropsTheSignature_isStillCaught() {
        seedEvents("e.one", "e.two");
        drain();

        jdbc.update("UPDATE audit_log_entries SET payload = ?::jsonb, signature = NULL, "
                        + "key_id = NULL, signature_scheme = NULL "
                        + "WHERE tenant_id = ? AND sequence_number = 2",
                "{\"forged\": true}", client.id());

        ChainVerificationResult result = verify();

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(2);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.PAYLOAD_HASH_MISMATCH);
    }

    /**
     * Rotation, on a real chain: the first entries carry the PREVIOUS key (rewritten through
     * the owner connection, as the earlier deployment would have written them), the later ones
     * carry the current key. Both verify, because the registry still publishes both public
     * keys — which is why a public key is never removed.
     */
    @Test
    void chainSpanningAKeyRotation_verifiesUnderBothKeyIds() {
        seedEvents("e.one", "e.two");
        drain();
        resignAsPreviousKey(1);
        resignAsPreviousKey(2);
        seedEvents("e.three");
        drain();

        assertThat(jdbc.queryForList("SELECT DISTINCT key_id FROM audit_log_entries "
                + "WHERE tenant_id = ?", String.class, client.id())).hasSize(2);

        ChainVerificationResult result = verify();

        assertThat(result.valid()).isTrue();
        assertThat(result.signatures().verified()).isEqualTo(3);
        assertThat(result.signatures().fullyVerified()).isTrue();
    }

    // --- helpers ---

    private void seedEvents(String... eventTypes) {
        TenantContext.setCurrentTenantId(client.id());
        try {
            for (String eventType : eventTypes) {
                fixture.recordOnly(client.id(), eventType);
            }
        } finally {
            TenantContext.clear();
        }
    }

    private void drain() {
        TenantContext.setCurrentTenantId(client.id());
        try {
            drainService.drainBatch(client.id(), properties.getBatchSize());
        } finally {
            TenantContext.clear();
        }
    }

    private ChainVerificationResult verify() {
        TenantContext.setCurrentTenantId(client.id());
        try {
            return verifier.verifyChain(client.id());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Rewrites one entry's signature as the PREVIOUS key would have made it: the same entry
     * hash, signed by the retired key and stamped with its key_id. Uses the owner connection,
     * since the runtime role cannot UPDATE the ledger (V21).
     */
    private void resignAsPreviousKey(long sequence) {
        String previousKeyId = keyIdOf(PREVIOUS_PAIR);
        String entryHash = jdbc.queryForObject("SELECT entry_hash FROM audit_log_entries "
                + "WHERE tenant_id = ? AND sequence_number = ?", String.class,
                client.id(), sequence);
        String signature =
                new AuditEntrySigner(PREVIOUS_PAIR.getPrivate(), previousKeyId).sign(entryHash);
        jdbc.update("UPDATE audit_log_entries SET signature = ?, key_id = ? "
                        + "WHERE tenant_id = ? AND sequence_number = ?",
                signature, previousKeyId, client.id(), sequence);
        // Sanity: the rewrite produced a real signature, not an empty string.
        assertThat(Base64.getDecoder().decode(signature)).hasSize(64);
    }
}
