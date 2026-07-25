package dev.cauce.governance.audit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.governance.support.AbstractGovernanceIntegrationTest;
import dev.cauce.governance.support.AuditedBusinessFixture;
import dev.cauce.tenancy.TenantService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end tests of the per-tenant hash chain — the payoff of the unit:
 * <ul>
 *   <li>a payload manipulated by a PRIVILEGED role (the owner connection, which bypasses the
 *       V21 append-only revoke) is detected at the exact sequence number;</li>
 *   <li>each tenant's chain starts at its own tenant-derived genesis and is fully
 *       independent of every other tenant's;</li>
 *   <li>the head row is the single source of both the next sequence number and the next
 *       {@code prev_hash};</li>
 *   <li>an owner-redacted payload (set to NULL) does NOT break verification — the chain
 *       verifies through the persisted {@code payload_hash}. This proves a TECHNICAL
 *       property (erasure compatibility), never a legal conclusion;</li>
 *   <li>a drainer restart mid-run continues the chain without duplicates or re-chaining;</li>
 *   <li>rows drained before the chain unit are honestly reported as a pre-chain prefix.</li>
 * </ul>
 * All real: real database, real roles, real drainer service, real verifier — no mocks.
 */
class AuditChainIT extends AbstractGovernanceIntegrationTest {

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
    private Tenant clientA;
    private Tenant clientB;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(adminDataSource);
        truncateAll();
        TenantContext.clear();
        operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        clientA = tenantService.createClient("Client A", partner.id());
        clientB = tenantService.createClient("Client B", partner.id());
        TenantContext.clear();
        // Seeding emits Family-B admin events by design; these ITs assert the CHAIN
        // mechanism in isolation, so the audit tables start empty (admin events are
        // asserted in cauce-tenancy's AuditAdminIT).
        jdbc.execute("TRUNCATE TABLE audit_chain_heads, audit_log_entries, audit_outbox");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // === MANIPULATION (the test that IS the unit) ===

    @Test
    void chain_payloadTamperedByPrivilegedRole_breaksAtExactSequence() {
        seedEvents(clientA.id(), "e.one", "e.two", "e.three", "e.four", "e.five");
        drainBatchAs(clientA.id(), properties.getBatchSize());
        assertThat(verifyAs(clientA.id()).valid()).isTrue();

        // The owner connection bypasses the append-only revoke — the privileged attacker.
        int updated = jdbc.update("UPDATE audit_log_entries "
                        + "SET payload = '{\"tampered\": true}'::jsonb "
                        + "WHERE tenant_id = ? AND sequence_number = 3", clientA.id());
        assertThat(updated).isEqualTo(1);

        ChainVerificationResult result = verifyAs(clientA.id());

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(3);
        assertThat(result.breakKind()).isEqualTo(ChainBreakKind.PAYLOAD_HASH_MISMATCH);
    }

    @Test
    void chain_withoutTampering_verifiesValid() {
        seedEvents(clientA.id(), "e.one", "e.two", "e.three");
        drainBatchAs(clientA.id(), properties.getBatchSize());

        ChainVerificationResult result = verifyAs(clientA.id());

        assertThat(result.valid()).isTrue();
        assertThat(result.chainedCount()).isEqualTo(3);
        assertThat(result.preChainCount()).isZero();
    }

    // === PER-TENANT CHAINS ===

    @Test
    void chain_twoTenants_independentChainsFromPerTenantGenesis() {
        seedEvents(clientA.id(), "a.one", "a.two");
        seedEvents(clientB.id(), "b.one", "b.two");

        // The scheduled bean stays disabled in tests; drive the same entry point by hand.
        new AuditOutboxDrainer(drainService, properties).drainAll();

        // Each tenant's first entry links to its OWN genesis — chains never touch.
        assertThat(firstPrevHash(clientA.id())).isEqualTo(hasher.genesisHash(clientA.id()));
        assertThat(firstPrevHash(clientB.id())).isEqualTo(hasher.genesisHash(clientB.id()));
        assertThat(verifyAs(clientA.id()).valid()).isTrue();
        assertThat(verifyAs(clientB.id()).valid()).isTrue();

        // No hash of A's chain appears anywhere in B's (and vice versa by symmetry).
        List<String> aHashes = jdbc.queryForList(
                "SELECT entry_hash FROM audit_log_entries WHERE tenant_id = ?",
                String.class, clientA.id());
        List<String> bHashes = jdbc.queryForList(
                "SELECT entry_hash FROM audit_log_entries WHERE tenant_id = ?",
                String.class, clientB.id());
        assertThat(aHashes).doesNotContainAnyElementsOf(bHashes);

        // RLS scopes verification: under A's context, B's rows are simply invisible.
        assertThat(countAs("audit_log_entries", clientA.id())).isEqualTo(2);
        assertThat(countAs("audit_chain_heads", clientA.id())).isEqualTo(1);
    }

    // === THE HEAD AS SINGLE SOURCE (the seam resolved) ===

    @Test
    void drain_headRow_isSingleSourceOfSequenceAndPrevHash() {
        seedEvents(clientA.id(), "e.one", "e.two", "e.three", "e.four");

        drainBatchAs(clientA.id(), 2);
        Map<String, Object> headAfterFirst = jdbc.queryForMap(
                "SELECT last_sequence_number, last_entry_hash FROM audit_chain_heads "
                        + "WHERE tenant_id = ?", clientA.id());
        assertThat(headAfterFirst.get("last_sequence_number")).isEqualTo(2L);

        drainBatchAs(clientA.id(), 2);

        // The first entry of the second batch took BOTH its sequence and its prev_hash from
        // that one head row — no second, divergent read anywhere.
        Map<String, Object> thirdEntry = jdbc.queryForMap(
                "SELECT prev_hash FROM audit_log_entries "
                        + "WHERE tenant_id = ? AND sequence_number = 3", clientA.id());
        assertThat(thirdEntry.get("prev_hash"))
                .isEqualTo(headAfterFirst.get("last_entry_hash"));

        Map<String, Object> headAfterSecond = jdbc.queryForMap(
                "SELECT last_sequence_number, last_entry_hash FROM audit_chain_heads "
                        + "WHERE tenant_id = ?", clientA.id());
        assertThat(headAfterSecond.get("last_sequence_number")).isEqualTo(4L);
        assertThat(headAfterSecond.get("last_entry_hash")).isEqualTo(jdbc.queryForObject(
                "SELECT entry_hash FROM audit_log_entries "
                        + "WHERE tenant_id = ? AND sequence_number = 4",
                String.class, clientA.id()));
        assertThat(verifyAs(clientA.id()).valid()).isTrue();
    }

    // === ERASURE COMPATIBILITY (technical property only) ===

    @Test
    void chain_redactedPayload_stillVerifiesValid() {
        seedEvents(clientA.id(), "e.one", "e.two", "e.three");
        drainBatchAs(clientA.id(), properties.getBatchSize());

        // Redaction is by construction a privileged owner operation (cauce_app cannot
        // UPDATE the ledger): the raw payload goes away, the persisted payload_hash stays.
        int updated = jdbc.update("UPDATE audit_log_entries SET payload = NULL "
                + "WHERE tenant_id = ? AND sequence_number = 2", clientA.id());
        assertThat(updated).isEqualTo(1);

        ChainVerificationResult result = verifyAs(clientA.id());

        assertThat(result.valid()).isTrue();
        assertThat(result.chainedCount()).isEqualTo(3);
    }

    // === RESTART / IDEMPOTENCY ===

    @Test
    void drain_restartMidRun_chainStaysContiguousAndValid() {
        seedEvents(clientA.id(), "e.one", "e.two", "e.three");

        assertThat(drainBatchAs(clientA.id(), 2)).isEqualTo(2);
        assertThat(drainBatchAs(clientA.id(), 2)).isEqualTo(1); // "restarted" drainer resumes
        assertThat(drainBatchAs(clientA.id(), 2)).isZero();     // fully drained: no-op

        assertThat(jdbc.queryForList(
                "SELECT sequence_number FROM audit_log_entries WHERE tenant_id = ? "
                        + "ORDER BY sequence_number", Long.class, clientA.id()))
                .containsExactly(1L, 2L, 3L);
        // The chain links across the batch boundary and verifies end to end.
        assertThat(jdbc.queryForObject(
                "SELECT prev_hash FROM audit_log_entries "
                        + "WHERE tenant_id = ? AND sequence_number = 3",
                String.class, clientA.id()))
                .isEqualTo(jdbc.queryForObject(
                        "SELECT entry_hash FROM audit_log_entries "
                                + "WHERE tenant_id = ? AND sequence_number = 2",
                        String.class, clientA.id()));
        assertThat(verifyAs(clientA.id()).valid()).isTrue();
    }

    // === PRE-CHAIN LEGACY EDGE ===

    @Test
    void chain_preChainRowsAsPrefix_verifyValidAndChainStartsAfterThem() {
        // Simulate rows drained by the pre-chain drainer (owner INSERT, hashes NULL) — none
        // exist in any real database (the recorder has no production callers), but a foreign
        // deployment could hold them and they must be handled honestly: no backfill.
        for (long seq = 1; seq <= 2; seq++) {
            jdbc.update("INSERT INTO audit_log_entries "
                            + "(id, tenant_id, sequence_number, outbox_id, event_type, payload, "
                            + "drained_at) VALUES (?, ?, ?, ?, ?, '{\"legacy\": true}'::jsonb, "
                            + "now())",
                    UUID.randomUUID(), clientA.id(), seq, UUID.randomUUID(), "legacy.event");
        }

        seedEvents(clientA.id(), "e.new1", "e.new2");
        drainBatchAs(clientA.id(), properties.getBatchSize());

        ChainVerificationResult result = verifyAs(clientA.id());

        assertThat(result.valid()).isTrue();
        assertThat(result.preChainCount()).isEqualTo(2); // honestly reported, not backfilled
        assertThat(result.chainedCount()).isEqualTo(2);
        // Numbering continued past the legacy rows; the chain itself starts at genesis.
        assertThat(jdbc.queryForObject(
                "SELECT prev_hash FROM audit_log_entries "
                        + "WHERE tenant_id = ? AND sequence_number = 3",
                String.class, clientA.id()))
                .isEqualTo(hasher.genesisHash(clientA.id()));
        assertThat(jdbc.queryForObject(
                "SELECT entry_hash FROM audit_log_entries "
                        + "WHERE tenant_id = ? AND sequence_number = 1",
                String.class, clientA.id())).isNull();
    }

    // --- helpers ---

    /** Captures one event per type for {@code tenantId}, each in its own committed tx. */
    private void seedEvents(UUID tenantId, String... eventTypes) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            for (String eventType : eventTypes) {
                fixture.recordOnly(tenantId, eventType);
            }
        } finally {
            TenantContext.clear();
        }
    }

    private int drainBatchAs(UUID tenantId, int batchSize) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return drainService.drainBatch(tenantId, batchSize);
        } finally {
            TenantContext.clear();
        }
    }

    private ChainVerificationResult verifyAs(UUID tenantId) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return verifier.verifyChain(tenantId);
        } finally {
            TenantContext.clear();
        }
    }

    private String firstPrevHash(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT prev_hash FROM audit_log_entries "
                        + "WHERE tenant_id = ? AND sequence_number = 1",
                String.class, tenantId);
    }
}
