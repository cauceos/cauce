package dev.cauce.governance.audit;

import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditEventRecorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.governance.support.AbstractGovernanceIntegrationTest;
import dev.cauce.governance.support.AuditedBusinessFixture;
import dev.cauce.tenancy.TenantService;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * End-to-end tests of the guaranteed-capture skeleton — the heart of the unit:
 * <ul>
 *   <li>the audit ledger is append-only AT THE DATABASE LAYER for the runtime role (the V21
 *       REVOKE, not code discipline);</li>
 *   <li>outbox capture is atomic with the business transaction it audits;</li>
 *   <li>draining assigns independent, contiguous per-tenant sequences and survives a
 *       restart mid-run without duplicating or skipping;</li>
 *   <li>both tables respect hierarchical RLS.</li>
 * </ul>
 * The scheduled drainer stays disabled ({@code application-test.properties}); tests drive a
 * hand-built drainer or the drain service directly, like the worker ITs do.
 */
class AuditCaptureIT extends AbstractGovernanceIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AuditedBusinessFixture fixture;

    @Autowired
    private AuditEventRecorder recorder;

    @Autowired
    private AuditOutboxDrainService drainService;

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
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // === APPEND-ONLY BY ROLE (the core of the unit) ===

    @Test
    void auditLog_asCauceApp_updateAndDeleteFailAtDatabaseLevel() {
        seedAndDrain(clientA.id(), "e.one");
        // The seeded ledger row was INSERTed by the drain service over the cauce_app
        // connection — INSERT is granted and works.
        assertThat(countLedgerRows(clientA.id())).isEqualTo(1);

        // UPDATE and DELETE fail by PRIVILEGE (V21 REVOKE), not by application code: the
        // statements run as cauce_app over a raw JDBC connection.
        assertThatThrownBy(() -> updateAs(clientA.id(),
                "UPDATE audit_log_entries SET event_type = 'tampered'"))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasStackTraceContaining("permission denied for table audit_log_entries");
        assertThatThrownBy(() -> updateAs(clientA.id(), "DELETE FROM audit_log_entries"))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasStackTraceContaining("permission denied for table audit_log_entries");

        // Nothing changed.
        assertThat(jdbc.queryForObject(
                "SELECT event_type FROM audit_log_entries", String.class)).isEqualTo("e.one");
    }

    // === TRANSACTIONAL-OUTBOX ATOMICITY ===

    @Test
    void record_withinBusinessTransaction_commitsBothRowsTogether() {
        TenantContext.setCurrentTenantId(clientA.id());
        UUID agentId = fixture.createAgentWithAudit(clientA.id(), false);

        Integer agents = jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE id = ?", Integer.class, agentId);
        assertThat(agents).isEqualTo(1);
        Map<String, Object> outboxRow = jdbc.queryForMap(
                "SELECT tenant_id, event_type, drain_status, payload FROM audit_outbox");
        assertThat(outboxRow.get("tenant_id")).isEqualTo(clientA.id());
        assertThat(outboxRow.get("event_type")).isEqualTo("test.agent_created");
        assertThat(outboxRow.get("drain_status")).isEqualTo("PENDING");
        assertThat(outboxRow.get("payload").toString()).contains(agentId.toString());
    }

    @Test
    void record_whenBusinessTxRollsBack_leavesNeitherRow() {
        TenantContext.setCurrentTenantId(clientA.id());

        assertThatThrownBy(() -> fixture.createAgentWithAudit(clientA.id(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated business failure");

        // Same fate for both writes: the rollback removed the business row AND the capture.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agents", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_outbox", Integer.class))
                .isZero();
    }

    @Test
    void record_withoutActiveTransaction_throws() {
        TenantContext.setCurrentTenantId(clientA.id());

        assertThatThrownBy(() -> recorder.record(
                new AuditEvent(clientA.id(), "e.orphan", Map.of())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_outbox", Integer.class))
                .isZero();
    }

    // === PER-TENANT DRAINING ===

    @Test
    void drain_twoTenants_assignsIndependentContiguousSequences() {
        seedEvents(clientA.id(), "a.one", "a.two", "a.three");
        seedEvents(clientB.id(), "b.one", "b.two", "b.three");

        new AuditOutboxDrainer(drainService, properties).drainAll();

        List<Map<String, Object>> aRows = ledgerRowsInSequence(clientA.id());
        assertThat(aRows).extracting(row -> row.get("sequence_number"))
                .containsExactly(1L, 2L, 3L);
        assertThat(aRows).extracting(row -> row.get("event_type"))
                .containsExactly("a.one", "a.two", "a.three"); // capture order preserved
        List<Map<String, Object>> bRows = ledgerRowsInSequence(clientB.id());
        assertThat(bRows).extracting(row -> row.get("sequence_number"))
                .containsExactly(1L, 2L, 3L); // B's own sequence, not interleaved with A's
        assertThat(bRows).extracting(row -> row.get("event_type"))
                .containsExactly("b.one", "b.two", "b.three");

        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM audit_outbox WHERE drain_status = 'PENDING'",
                Integer.class);
        assertThat(pending).isZero();
    }

    @Test
    void drain_restartMidRun_resumesWithoutDuplicatesOrGaps() {
        seedEvents(clientA.id(), "e.one", "e.two", "e.three");

        // First run drains a partial batch, then "the drainer dies". State is all in the
        // database, so a fresh run resumes exactly where the last committed batch ended.
        assertThat(drainBatchAs(clientA.id(), 2)).isEqualTo(2);
        assertThat(drainBatchAs(clientA.id(), 2)).isEqualTo(1);
        assertThat(drainBatchAs(clientA.id(), 2)).isZero(); // fully drained: a re-run is a no-op

        assertThat(ledgerRowsInSequence(clientA.id()))
                .extracting(row -> row.get("sequence_number"))
                .containsExactly(1L, 2L, 3L);
        assertThat(ledgerRowsInSequence(clientA.id()))
                .extracting(row -> row.get("event_type"))
                .containsExactly("e.one", "e.two", "e.three");
    }

    // === RLS ===

    @Test
    void auditTables_areFilteredByRlsHierarchy() {
        seedAndDrain(clientA.id(), "e.one");

        for (String table : List.of("audit_outbox", "audit_log_entries", "audit_chain_heads")) {
            assertThat(countAs(table, clientA.id())).as("%s owner", table).isEqualTo(1);
            assertThat(countAs(table, partner.id())).as("%s partner", table).isEqualTo(1);
            assertThat(countAs(table, operator.id())).as("%s operator", table).isEqualTo(1);
            assertThat(countAs(table, clientB.id())).as("%s sibling", table).isZero();
            assertThat(countAs(table, null)).as("%s no context", table).isZero();
        }
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

    private void seedAndDrain(UUID tenantId, String eventType) {
        seedEvents(tenantId, eventType);
        assertThat(drainBatchAs(tenantId, properties.getBatchSize())).isEqualTo(1);
    }

    private int drainBatchAs(UUID tenantId, int batchSize) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return drainService.drainBatch(tenantId, batchSize);
        } finally {
            TenantContext.clear();
        }
    }

    private long countLedgerRows(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log_entries WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private List<Map<String, Object>> ledgerRowsInSequence(UUID tenantId) {
        return jdbc.queryForList(
                "SELECT sequence_number, event_type FROM audit_log_entries "
                        + "WHERE tenant_id = ? ORDER BY sequence_number", tenantId);
    }
}
