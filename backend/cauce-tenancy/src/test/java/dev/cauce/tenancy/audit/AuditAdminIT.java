package dev.cauce.tenancy.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.audit.AuditContentHash;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.governance.audit.AuditChainVerifier;
import dev.cauce.governance.audit.AuditOutboxDrainService;
import dev.cauce.governance.audit.ChainVerificationResult;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.ApiKeyCreationResult;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests of the Family-B (administration) audit wiring — the heart of the unit:
 * real admin operations (create tenant/agent, issue/revoke API key) write their audit
 * record atomically in the operation's transaction, into the chain of the SUBJECT tenant
 * with the acting tenant as actor, and NO secret material (key plaintext, HMAC hash) ever
 * reaches the append-only sink. The drain and verifier are the real governance beans over
 * the same database.
 * <ul>
 *   <li>HIERARCHICAL PLACEMENT: a partner creating a client audits into the CLIENT's chain
 *       (the chicken-and-egg RLS seam, proven real), and ascending visibility lets the
 *       partner read it;</li>
 *   <li>ADMIN ATOMICITY: a rolled-back admin operation leaves neither the entity nor the
 *       audit record;</li>
 *   <li>SECRETS NEVER IN THE SINK: asserted against the REAL plaintext and stored hash;</li>
 *   <li>per-tenant order and RLS isolation of the admin trail.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AuditAdminIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private FailingAdminFixture fixture;

    @Autowired
    private AuditOutboxDrainService drainService;

    @Autowired
    private AuditChainVerifier chainVerifier;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE audit_chain_heads, audit_log_entries, audit_outbox, "
                + "llm_usage_records, channel_configs, api_keys, ingest_idempotency_records, "
                + "pending_invocations, messages, conversations, agents, tenants CASCADE");
        TenantContext.clear();
        operator = tenantService.bootstrapOperator("Operator"); // genesis: no tx, no audit
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // === HIERARCHICAL PLACEMENT (the chicken-and-egg seam, real) ===

    @Test
    void createClient_byPartner_writesEventInSubjectChainWithPartnerAsActor() {
        TenantContext.setCurrentTenantId(partner.id());
        Tenant client = tenantService.createClient("Client Co", partner.id());
        TenantContext.clear();

        // The event was captured in the SAME tx that created the client, in the CLIENT's
        // chain — written from the PARTNER's RLS context (subject is its direct child).
        drainAs(client.id());
        List<String> types = eventTypesInChain(client.id());
        assertThat(types).containsExactly("admin.tenant.created");
        assertThat(payloadField(client.id(), 1, "actor_tenant_id"))
                .isEqualTo(partner.id().toString());
        assertThat(payloadField(client.id(), 1, "parent_tenant_id"))
                .isEqualTo(partner.id().toString());
        assertThat(payloadField(client.id(), 1, "tier")).isEqualTo("CLIENT");
        // The business name never enters the sink.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log_entries "
                + "WHERE payload::text LIKE '%Client Co%'", Integer.class)).isZero();

        // Ascending visibility: the ACTOR (partner) reads the subject's chain — both via
        // RLS as the runtime role and via the verifier running under the partner's context.
        assertThat(countLedgerVisibleAs(partner.id(), client.id())).isEqualTo(1);
        ChainVerificationResult verified = verifyAs(partner.id(), client.id());
        assertThat(verified.valid()).isTrue();
        assertThat(verified.chainedCount()).isEqualTo(1);
    }

    // === ADMIN ATOMICITY (the test that IS the unit) ===

    @Test
    void adminOperation_whenTxRollsBack_leavesNeitherEntityNorAuditRecord() {
        Tenant client = seedClient("Client");

        TenantContext.setCurrentTenantId(client.id());
        assertThatThrownBy(() -> fixture.createAgentThenFail(client.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated admin failure");
        TenantContext.clear();

        // Absence of the fact ⇔ absence of the record, with a real admin operation.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agents", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_outbox "
                + "WHERE event_type = 'admin.agent.created'", Integer.class)).isZero();
    }

    // === SECRETS NEVER IN THE SINK ===

    @Test
    void apiKeyIssuedAndRevoked_sinkNeverContainsPlaintextNorHash() {
        Tenant client = seedClient("Client");

        TenantContext.setCurrentTenantId(client.id());
        ApiKeyCreationResult created = apiKeyService.createApiKey(client.id(), "Production");
        apiKeyService.revokeApiKey(created.apiKey().id());
        TenantContext.clear();

        String plaintext = created.plaintextKey();
        String storedHash = jdbc.queryForObject(
                "SELECT key_hash FROM api_keys WHERE id = ?", String.class,
                created.apiKey().id());
        assertThat(storedHash).isNotBlank();

        // Neither the plaintext nor the stored HMAC hash appears ANYWHERE in the audit
        // trail — asserted against the real secret material.
        for (String table : List.of("audit_outbox")) {
            Integer leaks = jdbc.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE payload::text LIKE ? "
                            + "OR payload::text LIKE ?",
                    Integer.class, "%" + plaintext + "%", "%" + storedHash + "%");
            assertThat(leaks).as("secret material in %s", table).isZero();
        }

        drainAs(client.id());
        assertThat(eventTypesInChain(client.id())).containsExactly(
                "admin.tenant.created", "admin.apikey.issued", "admin.apikey.revoked");
        assertThat(payloadField(client.id(), 2, "api_key_id"))
                .isEqualTo(created.apiKey().id().toString());
        assertThat(payloadField(client.id(), 2, "key_prefix"))
                .isEqualTo(created.apiKey().keyPrefix());
        assertThat(payloadField(client.id(), 3, "actor_tenant_id"))
                .isEqualTo(client.id().toString());
        Integer ledgerLeaks = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log_entries WHERE payload::text LIKE ? "
                        + "OR payload::text LIKE ?",
                Integer.class, "%" + plaintext + "%", "%" + storedHash + "%");
        assertThat(ledgerLeaks).isZero();
        assertThat(verifyAs(client.id(), client.id()).valid()).isTrue();
    }

    @Test
    void agentCreated_promptEntersOnlyAsHash() {
        Tenant client = seedClient("Client");

        TenantContext.setCurrentTenantId(client.id());
        Agent agent = agentService.createAgent(client.id(), "Bot",
                "Confidential sales playbook v3.", "anthropic", "claude-sonnet-4-7");
        TenantContext.clear();

        drainAs(client.id());
        assertThat(payloadField(client.id(), 2, "prompt_hash")).isEqualTo(
                AuditContentHash.of(client.id(), "Confidential sales playbook v3."));
        assertThat(payloadField(client.id(), 2, "agent_id")).isEqualTo(agent.id().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log_entries "
                        + "WHERE payload::text LIKE '%Confidential sales playbook%'",
                Integer.class)).isZero();
    }

    // === ORDER, PER-TENANT, RLS ===

    @Test
    void adminEvents_perTenantOrderAndRlsIsolation() {
        Tenant clientA = seedClient("Client A");
        Tenant clientB = seedClient("Client B");
        for (Tenant client : List.of(clientA, clientB)) {
            TenantContext.setCurrentTenantId(client.id());
            agentService.createAgent(client.id(), "Bot", "p.", "anthropic", "claude-sonnet-4-7");
            apiKeyService.createApiKey(client.id(), "k");
            TenantContext.clear();
        }

        drainAs(clientA.id());
        drainAs(clientB.id());

        for (Tenant client : List.of(clientA, clientB)) {
            assertThat(eventTypesInChain(client.id())).containsExactly(
                    "admin.tenant.created", "admin.agent.created", "admin.apikey.issued");
            assertThat(verifyAs(client.id(), client.id()).valid()).isTrue();
        }
        // RLS: a client sees only its own admin trail (3 rows), never the sibling's.
        assertThat(countLedgerVisibleAs(clientA.id(), clientA.id())).isEqualTo(3);
        assertThat(countLedgerVisibleAs(clientA.id(), clientB.id())).isZero();
    }

    // --- helpers ---

    private Tenant seedClient(String name) {
        TenantContext.setCurrentTenantId(partner.id());
        try {
            return tenantService.createClient(name, partner.id());
        } finally {
            TenantContext.clear();
        }
    }

    private void drainAs(UUID tenantId) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            drainService.drainBatch(tenantId, 100);
        } finally {
            TenantContext.clear();
        }
    }

    /** Verifies {@code subjectTenantId}'s chain under {@code contextTenantId}'s context. */
    private ChainVerificationResult verifyAs(UUID contextTenantId, UUID subjectTenantId) {
        TenantContext.setCurrentTenantId(contextTenantId);
        try {
            return chainVerifier.verifyChain(subjectTenantId);
        } finally {
            TenantContext.clear();
        }
    }

    private List<String> eventTypesInChain(UUID tenantId) {
        return jdbc.queryForList("SELECT event_type FROM audit_log_entries "
                + "WHERE tenant_id = ? ORDER BY sequence_number", String.class, tenantId);
    }

    private String payloadField(UUID tenantId, long sequenceNumber, String field) {
        return jdbc.queryForObject(
                "SELECT payload ->> ? FROM audit_log_entries "
                        + "WHERE tenant_id = ? AND sequence_number = ?",
                String.class, field, tenantId, sequenceNumber);
    }

    /**
     * Counts {@code subjectTenantId}'s ledger rows visible under {@code contextTenantId},
     * querying as the runtime cauce_app role so Row-Level Security applies for real.
     */
    private long countLedgerVisibleAs(UUID contextTenantId, UUID subjectTenantId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE cauce_app");
                st.execute("SET LOCAL app.current_tenant_id = '" + contextTenantId + "'");
                try (ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM audit_log_entries WHERE tenant_id = '"
                                + subjectTenantId + "'")) {
                    rs.next();
                    return rs.getLong(1);
                }
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
