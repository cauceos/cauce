package dev.cauce.orchestration.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.audit.AuditContentHash;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.governance.audit.AuditOutboxDrainService;
import dev.cauce.governance.audit.AuditChainVerifier;
import dev.cauce.governance.audit.ChainVerificationResult;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import dev.cauce.orchestration.InboundMessageResult;
import dev.cauce.orchestration.InboundMessageService;
import dev.cauce.orchestration.PendingInvocation;
import dev.cauce.orchestration.PendingInvocationService;
import dev.cauce.orchestration.service.MockLlmProvider;
import dev.cauce.orchestration.service.OrchestratorService;
import dev.cauce.orchestration.support.AbstractOrchestrationIntegrationTest;
import dev.cauce.orchestration.support.FailingIngestFixture;
import dev.cauce.orchestration.support.MockLlmProviderTestConfig;
import dev.cauce.orchestration.worker.PendingInvocationWorker;
import dev.cauce.orchestration.worker.PendingInvocationWorkerProperties;
import dev.cauce.orchestration.worker.WorkerIdentity;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.TenantService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end tests of the first REAL audit callers — the heart of the unit: a real
 * invocation's conduct events are captured atomically with the business facts they audit,
 * flow through the outbox into the per-tenant hash chain, and bind to the exact message
 * content by hash. Everything is real (ingest, orchestrator loop, worker transitions,
 * governance drain + verifier over the same database) except the LLM provider, which is the
 * standard mock.
 * <ul>
 *   <li>ATOMICITY WITH A REAL FACT: a rolled-back ingest leaves neither the USER message
 *       nor the audit record — absence of the fact ⇔ absence of the record;</li>
 *   <li>CONTENT BINDING: the audited content_hash equals the hash of the real text, and
 *       tampering the business row breaks the match (detectable);</li>
 *   <li>ERASURE COMPATIBILITY: erasing the message text in its mutable business table
 *       neither breaks the chain nor removes the audited hash — a TECHNICAL property, no
 *       legal claim;</li>
 *   <li>ORDER, TENANT ISOLATION, and RLS over the chained conduct events.</li>
 * </ul>
 */
@Import(MockLlmProviderTestConfig.class)
class AuditConductIT extends AbstractOrchestrationIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private InboundMessageService inboundMessageService;

    @Autowired
    private FailingIngestFixture failingIngestFixture;

    @Autowired
    private PendingInvocationService pendingInvocationService;

    @Autowired
    private OrchestratorService orchestratorService;

    @Autowired
    private WorkerIdentity workerIdentity;

    @Autowired
    private PendingInvocationWorkerProperties workerProperties;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockLlmProvider mockLlmProvider;

    @Autowired
    private AuditOutboxDrainService drainService;

    @Autowired
    private AuditChainVerifier chainVerifier;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant clientA;
    private Tenant clientB;
    private Agent agentA;
    private Agent agentB;

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
        TenantContext.setCurrentTenantId(clientA.id());
        agentA = agentService.createAgent(clientA.id(), "BotA",
                "You are helpful.", "anthropic", "claude-sonnet-4-7");
        TenantContext.setCurrentTenantId(clientB.id());
        agentB = agentService.createAgent(clientB.id(), "BotB",
                "You are helpful.", "anthropic", "claude-sonnet-4-7");
        TenantContext.clear();
        // Seeding emits Family-B admin events by design; this IT asserts Family A
        // (conduct) in isolation, so the audit tables start empty (admin events are
        // asserted in cauce-tenancy's AuditAdminIT).
        jdbc.execute("TRUNCATE TABLE audit_chain_heads, audit_log_entries, audit_outbox");
        mockLlmProvider.respondWith(invocation ->
                new LlmResponse("default reply", List.of(), FinishReason.STOP, LlmUsage.of(1, 1)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // === ATOMICITY WITH A REAL FACT (the test that IS the unit) + CONTENT BINDING ===

    @Test
    void ingest_realInvocation_writesConductEventsChainedInOrderWithRealContentHash() {
        mockLlmProvider.respondWith(invocation -> new LlmResponse("Claro, te ayudo.",
                List.of(), FinishReason.STOP, LlmUsage.of(5, 5)));

        InboundMessageResult result = ingestAndProcess(clientA, agentA, "Necesito una cita");
        drainAs(clientA);

        // The tenant's chain holds exactly the two conduct events, in fact order, chained.
        List<Map<String, Object>> rows = chainedRows(clientA.id());
        assertThat(rows).extracting(row -> row.get("event_type"))
                .containsExactly("conduct.message.received", "conduct.agent.responded");
        assertThat(verifyAs(clientA).valid()).isTrue();

        // Payloads carry the REAL ids and the hash of the REAL content — never the text.
        assertThat(payloadField(clientA.id(), 1, "invocation_id"))
                .isEqualTo(result.invocationId().toString());
        assertThat(payloadField(clientA.id(), 1, "message_id"))
                .isEqualTo(result.messageId().toString());
        assertThat(payloadField(clientA.id(), 1, "content_hash"))
                .isEqualTo(AuditContentHash.of(clientA.id(), "Necesito una cita"));
        assertThat(payloadField(clientA.id(), 2, "finish_reason")).isEqualTo("STOP");
        assertThat(payloadField(clientA.id(), 2, "rounds")).isEqualTo("1");
        assertThat(payloadField(clientA.id(), 2, "content_hash"))
                .isEqualTo(AuditContentHash.of(clientA.id(), "Claro, te ayudo."));
        Integer rawTextAnywhere = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log_entries "
                        + "WHERE payload::text LIKE '%Necesito una cita%' "
                        + "OR payload::text LIKE '%Claro, te ayudo.%'", Integer.class);
        assertThat(rawTextAnywhere).isZero();
    }

    @Test
    void ingest_whenBusinessTxRollsBack_leavesNeitherMessageNorAuditRecord() {
        TenantContext.setCurrentTenantId(clientA.id());
        assertThatThrownBy(() -> failingIngestFixture.ingestThenFail(
                agentA.id(), "api", "user-rollback", "Mensaje condenado"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated business failure");
        TenantContext.clear();

        // Absence of the fact ⇔ absence of the record: the rollback removed the USER
        // message, the invocation, AND the audit capture together.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_invocations",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_outbox", Integer.class))
                .isZero();
    }

    @Test
    void contentHash_whenBusinessRowTampered_noLongerMatchesAuditedHash() {
        InboundMessageResult result = ingestAndProcess(clientA, agentA, "Texto original");
        drainAs(clientA);
        String auditedHash = payloadField(clientA.id(), 1, "content_hash");
        assertThat(auditedHash).isEqualTo(AuditContentHash.of(clientA.id(), "Texto original"));

        // A privileged edit of the mutable business row is detectable: the stored text no
        // longer hashes to what the chain committed to at ingest time.
        jdbc.update("UPDATE messages SET content = 'texto manipulado' WHERE id = ?",
                result.messageId());
        String storedContent = jdbc.queryForObject(
                "SELECT content FROM messages WHERE id = ?", String.class, result.messageId());

        assertThat(AuditContentHash.of(clientA.id(), storedContent)).isNotEqualTo(auditedHash);
        // The audit record itself was not touched: the chain still verifies.
        assertThat(verifyAs(clientA).valid()).isTrue();
    }

    // === ERASURE COMPATIBILITY (technical property only) ===

    @Test
    void chain_whenUserMessageContentErased_stillVerifiesAndHashRemains() {
        InboundMessageResult result = ingestAndProcess(clientA, agentA, "Dato personal: 612345678");
        drainAs(clientA);
        String auditedHash = payloadField(clientA.id(), 1, "content_hash");

        // Concrete erasure: the text lives in the MUTABLE business table, where deletion
        // operates. The chain committed only to its hash.
        jdbc.update("UPDATE messages SET content = '[erased]' WHERE id = ?", result.messageId());

        assertThat(verifyAs(clientA).valid()).isTrue();
        assertThat(payloadField(clientA.id(), 1, "content_hash")).isEqualTo(auditedHash);
        // Proves "a message with this exact hash was processed" without recovering the text.
        assertThat(auditedHash).isEqualTo(
                AuditContentHash.of(clientA.id(), "Dato personal: 612345678"));
    }

    // === ORDER, TENANT ISOLATION, RLS ===

    @Test
    void conduct_twoTenants_independentOrderedChains() {
        ingestAndProcess(clientA, agentA, "Hola de A");
        ingestAndProcess(clientB, agentB, "Hola de B");
        drainAs(clientA);
        drainAs(clientB);

        for (Tenant tenant : List.of(clientA, clientB)) {
            List<Map<String, Object>> rows = chainedRows(tenant.id());
            assertThat(rows).extracting(row -> row.get("event_type"))
                    .containsExactly("conduct.message.received", "conduct.agent.responded");
            assertThat(rows).extracting(row -> row.get("sequence_number"))
                    .containsExactly(1L, 2L); // each tenant's own chain, no interleaving
            assertThat(verifyAs(tenant).valid()).isTrue();
        }
        assertThat(payloadField(clientA.id(), 1, "content_hash"))
                .isEqualTo(AuditContentHash.of(clientA.id(), "Hola de A"));
        assertThat(payloadField(clientB.id(), 1, "content_hash"))
                .isEqualTo(AuditContentHash.of(clientB.id(), "Hola de B"));

        // RLS: a tenant sees only its own conduct records.
        assertThat(countAs("audit_log_entries", clientA.id())).isEqualTo(2);
        assertThat(countAs("audit_log_entries", clientB.id())).isEqualTo(2);
        assertThat(countAs("audit_log_entries", null)).isZero();
    }

    // === TERMINAL FAILURE ===

    @Test
    void invocationFailure_writesFailedEventInTerminalTransitionTx() {
        mockLlmProvider.respondWith(invocation -> {
            throw new LlmAuthenticationException("anthropic", "claude-sonnet-4-7",
                    "401 invalid api key");
        });

        InboundMessageResult result = ingestAndProcess(clientA, agentA, "Hola");
        drainAs(clientA);

        List<Map<String, Object>> rows = chainedRows(clientA.id());
        assertThat(rows).extracting(row -> row.get("event_type"))
                .containsExactly("conduct.message.received", "conduct.invocation.failed");
        assertThat(payloadField(clientA.id(), 2, "invocation_id"))
                .isEqualTo(result.invocationId().toString());
        assertThat(payloadField(clientA.id(), 2, "failure_type")).isEqualTo("LLM_ERROR");
        // The raw provider error never enters the chain (it can echo content).
        Integer rawError = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log_entries WHERE payload::text LIKE '%401%'",
                Integer.class);
        assertThat(rawError).isZero();
        assertThat(verifyAs(clientA).valid()).isTrue();
    }

    // --- helpers ---

    /**
     * Ingests {@code content} for {@code agent} under {@code tenant} and processes the
     * invocation synchronously (claim + hand-built worker over the real beans), mirroring
     * {@code LlmUsageRecordingIT}.
     */
    private InboundMessageResult ingestAndProcess(Tenant tenant, Agent agent, String content) {
        TenantContext.setCurrentTenantId(tenant.id());
        InboundMessageResult result = inboundMessageService.ingest(
                agent.id(), "api", "user-" + UUID.randomUUID(), content);
        TenantContext.clear(); // the claim is cross-tenant; the worker sets the row's tenant
        List<PendingInvocation> claimed =
                pendingInvocationService.claimNextBatch(workerIdentity.getId(), 10);
        assertThat(claimed).hasSize(1);
        PendingInvocationWorker syncWorker = new PendingInvocationWorker(pendingInvocationService,
                orchestratorService, workerIdentity, new SyncTaskExecutor(), workerProperties,
                applicationContext);
        syncWorker.processInvocation(claimed.get(0));
        return result;
    }

    private void drainAs(Tenant tenant) {
        TenantContext.setCurrentTenantId(tenant.id());
        try {
            drainService.drainBatch(tenant.id(), 100);
        } finally {
            TenantContext.clear();
        }
    }

    private ChainVerificationResult verifyAs(Tenant tenant) {
        TenantContext.setCurrentTenantId(tenant.id());
        try {
            return chainVerifier.verifyChain(tenant.id());
        } finally {
            TenantContext.clear();
        }
    }

    private List<Map<String, Object>> chainedRows(UUID tenantId) {
        return jdbc.queryForList(
                "SELECT sequence_number, event_type FROM audit_log_entries "
                        + "WHERE tenant_id = ? ORDER BY sequence_number", tenantId);
    }

    private String payloadField(UUID tenantId, long sequenceNumber, String field) {
        return jdbc.queryForObject(
                "SELECT payload ->> ? FROM audit_log_entries "
                        + "WHERE tenant_id = ? AND sequence_number = ?",
                String.class, field, tenantId, sequenceNumber);
    }
}
