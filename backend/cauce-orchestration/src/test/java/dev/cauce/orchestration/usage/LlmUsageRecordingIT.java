package dev.cauce.orchestration.usage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tool.ToolCall;
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
import dev.cauce.orchestration.worker.PendingInvocationWorker;
import dev.cauce.orchestration.worker.PendingInvocationWorkerProperties;
import dev.cauce.orchestration.worker.WorkerIdentity;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.TenantService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end tests of the per-tenant LLM usage ledger: a real ingest → claim → orchestrator
 * loop run (synchronously on the test thread, with a mock provider) must persist one
 * {@code llm_usage_records} row per LLM call, attributed to the agent's owning tenant, and
 * the rows must respect hierarchical RLS. Column-level asserts read through the admin
 * (owner) connection; RLS is exercised through the runtime cauce_app role via
 * {@link #countAs}.
 */
@Import(LlmUsageRecordingIT.UsageMockProviderConfig.class)
class LlmUsageRecordingIT extends AbstractOrchestrationIntegrationTest {

    @TestConfiguration
    static class UsageMockProviderConfig {
        @Bean
        MockLlmProvider mockLlmProvider() {
            return new MockLlmProvider();
        }
    }

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private InboundMessageService inboundMessageService;

    @Autowired
    private PendingInvocationService pendingInvocationService;

    @Autowired
    private OrchestratorService orchestratorService;

    @Autowired
    private WorkerIdentity workerIdentity;

    @Autowired
    private PendingInvocationWorkerProperties properties;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockLlmProvider mockLlmProvider;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant client;
    private Tenant siblingClient;
    private Agent agent;

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
        siblingClient = tenantService.createClient("Sibling Client", partner.id());
        TenantContext.setCurrentTenantId(client.id());
        agent = agentService.createAgent(client.id(), "DentalBot",
                "You are a dentist receptionist.", "anthropic", "claude-sonnet-4-7");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void ingestAndProcess_singleRound_persistsUsageAttributedToTenant() {
        mockLlmProvider.respondWith(invocation ->
                new LlmResponse("Hola, soy un agente", List.of(), FinishReason.STOP,
                        LlmUsage.of(7, 9)));

        InboundMessageResult result = ingestAndProcess("Hola");

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM llm_usage_records");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("tenant_id")).isEqualTo(client.id());
        assertThat(row.get("agent_id")).isEqualTo(agent.id());
        assertThat(row.get("conversation_id")).isEqualTo(result.conversationId());
        assertThat(row.get("invocation_id")).isEqualTo(result.invocationId());
        assertThat(row.get("provider")).isEqualTo("anthropic");
        assertThat(row.get("model")).isEqualTo("claude-sonnet-4-7");
        assertThat(row.get("round_index")).isEqualTo(0);
        assertThat(row.get("input_tokens")).isEqualTo(7);
        assertThat(row.get("output_tokens")).isEqualTo(9);
        assertThat(row.get("total_tokens")).isEqualTo(16);
        assertThat(row.get("finish_reason")).isEqualTo("STOP");
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void ingestAndProcess_oneToolRound_persistsOneRecordPerLlmCall() {
        AtomicInteger calls = new AtomicInteger();
        mockLlmProvider.respondWith(invocation -> calls.getAndIncrement() == 0
                ? new LlmResponse("", List.of(new ToolCall("call-1", "get_current_time", Map.of())),
                        FinishReason.TOOL_USE, LlmUsage.of(3, 4))
                : new LlmResponse("It is late.", List.of(), FinishReason.STOP, LlmUsage.of(5, 6)));

        InboundMessageResult result = ingestAndProcess("What time is it?");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM llm_usage_records ORDER BY round_index");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("round_index")).isEqualTo(0);
        assertThat(rows.get(0).get("finish_reason")).isEqualTo("TOOL_USE");
        assertThat(rows.get(0).get("total_tokens")).isEqualTo(7);
        assertThat(rows.get(1).get("round_index")).isEqualTo(1);
        assertThat(rows.get(1).get("finish_reason")).isEqualTo("STOP");
        assertThat(rows.get(1).get("total_tokens")).isEqualTo(11);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("tenant_id")).isEqualTo(client.id());
            assertThat(row.get("invocation_id")).isEqualTo(result.invocationId());
        });
    }

    @Test
    void usageRecords_areFilteredByRlsHierarchy() {
        mockLlmProvider.respondWith(invocation ->
                new LlmResponse("Hola", List.of(), FinishReason.STOP, LlmUsage.of(1, 1)));

        ingestAndProcess("Hola");

        assertThat(countAs("llm_usage_records", client.id())).isEqualTo(1);   // owner
        assertThat(countAs("llm_usage_records", partner.id())).isEqualTo(1);  // its partner
        assertThat(countAs("llm_usage_records", operator.id())).isEqualTo(1); // its operator
        assertThat(countAs("llm_usage_records", siblingClient.id())).isZero(); // sibling: no
        assertThat(countAs("llm_usage_records", null)).isZero();              // no context
    }

    /**
     * Ingests {@code content} as the seeded client and processes the resulting invocation
     * synchronously on the test thread (claim + a hand-built worker over the real beans).
     */
    private InboundMessageResult ingestAndProcess(String content) {
        InboundMessageResult result =
                inboundMessageService.ingest(agent.id(), "api", "user-" + UUID.randomUUID(), content);
        TenantContext.clear(); // the claim is cross-tenant; the worker sets the row's tenant
        List<PendingInvocation> claimed =
                pendingInvocationService.claimNextBatch(workerIdentity.getId(), 10);
        assertThat(claimed).hasSize(1);
        PendingInvocationWorker syncWorker = new PendingInvocationWorker(pendingInvocationService,
                orchestratorService, workerIdentity, new SyncTaskExecutor(), properties,
                applicationContext);
        syncWorker.processInvocation(claimed.get(0));
        return result;
    }
}
