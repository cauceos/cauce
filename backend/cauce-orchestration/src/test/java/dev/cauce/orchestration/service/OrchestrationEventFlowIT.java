package dev.cauce.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import dev.cauce.orchestration.InboundMessageResult;
import dev.cauce.orchestration.InboundMessageService;
import dev.cauce.orchestration.PendingInvocation;
import dev.cauce.orchestration.PendingInvocationService;
import dev.cauce.orchestration.events.ContextAssembled;
import dev.cauce.orchestration.events.InvocationCompleted;
import dev.cauce.orchestration.events.InvocationFailed;
import dev.cauce.orchestration.events.InvocationFailureType;
import dev.cauce.orchestration.events.InvocationRequested;
import dev.cauce.orchestration.events.LlmInvoked;
import dev.cauce.orchestration.events.LlmResponded;
import dev.cauce.orchestration.events.OrchestrationEvent;
import dev.cauce.orchestration.events.ToolCallRequested;
import dev.cauce.orchestration.events.ToolExecuted;
import dev.cauce.orchestration.support.AbstractOrchestrationIntegrationTest;
import dev.cauce.orchestration.support.MockLlmProviderTestConfig;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * End-to-end assertions on the invocation lifecycle event stream: ingest → claim →
 * synchronous worker processing, all on the test thread ({@link ApplicationEvents} only
 * records events published from it — the scheduled worker stays disabled and a hand-built
 * worker with a {@link SyncTaskExecutor} stands in, wired with the real beans).
 */
@RecordApplicationEvents
@Import(MockLlmProviderTestConfig.class)
class OrchestrationEventFlowIT extends AbstractOrchestrationIntegrationTest {

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

    @Autowired
    private ApplicationEvents applicationEvents;

    private Agent agent;

    @BeforeEach
    void setUp() {
        truncateAll();
        TenantContext.clear();
        Tenant operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        Tenant partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        Tenant client = tenantService.createClient("Client", partner.id());
        TenantContext.setCurrentTenantId(client.id());
        agent = agentService.createAgent(client.id(), "DentalBot",
                "You are a dentist receptionist.", "anthropic", "claude-sonnet-4-7");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void ingestAndProcess_simpleReply_emitsTheFullLifecycleSequence() {
        mockLlmProvider.respondWith(invocation ->
                new LlmResponse("Hola, soy un agente", List.of(), FinishReason.STOP,
                        LlmUsage.of(7, 9)));

        InboundMessageResult result = ingestAndProcess("Hola");

        List<OrchestrationEvent> events = recordedEvents();
        assertThat(events).hasExactlyElementsOfTypes(InvocationRequested.class,
                ContextAssembled.class, LlmInvoked.class, LlmResponded.class,
                InvocationCompleted.class);
        assertThat(events).allSatisfy(event ->
                assertThat(event.invocationId()).isEqualTo(result.invocationId()));

        InvocationRequested requested = (InvocationRequested) events.get(0);
        assertThat(requested.agentId()).isEqualTo(agent.id());
        assertThat(requested.tenantId()).isEqualTo(agent.tenantId());
        assertThat(requested.conversationId()).isEqualTo(result.conversationId());
        assertThat(requested.messageId()).isEqualTo(result.messageId());

        LlmResponded responded = (LlmResponded) events.get(3);
        assertThat(responded.provider()).isEqualTo("anthropic");
        assertThat(responded.finishReason()).isEqualTo("STOP");
        assertThat(responded.inputTokens()).isEqualTo(7);
        assertThat(responded.outputTokens()).isEqualTo(9);
        assertThat(responded.totalTokens()).isEqualTo(16);

        assertThat(((InvocationCompleted) events.get(4)).roundCount()).isEqualTo(1);
    }

    @Test
    void ingestAndProcess_oneToolRound_emitsToolEventsBetweenTheTwoLlmRounds() {
        AtomicInteger calls = new AtomicInteger();
        mockLlmProvider.respondWith(invocation -> calls.getAndIncrement() == 0
                ? new LlmResponse("", List.of(new ToolCall("call-1", "get_current_time", Map.of())),
                        FinishReason.TOOL_USE, LlmUsage.of(3, 4))
                : new LlmResponse("It is late.", List.of(), FinishReason.STOP, LlmUsage.of(5, 6)));

        InboundMessageResult result = ingestAndProcess("What time is it?");

        List<OrchestrationEvent> events = recordedEvents();
        assertThat(events).hasExactlyElementsOfTypes(InvocationRequested.class,
                ContextAssembled.class, LlmInvoked.class, LlmResponded.class,     // round 0
                ToolCallRequested.class, ToolExecuted.class,
                ContextAssembled.class, LlmInvoked.class, LlmResponded.class,     // round 1
                InvocationCompleted.class);
        assertThat(events).allSatisfy(event ->
                assertThat(event.invocationId()).isEqualTo(result.invocationId()));

        assertThat(((LlmResponded) events.get(3)).finishReason()).isEqualTo("TOOL_USE");
        ToolExecuted executed = (ToolExecuted) events.get(5);
        assertThat(executed.toolName()).isEqualTo("get_current_time");
        assertThat(executed.isError()).isFalse();
        assertThat(((ContextAssembled) events.get(6)).roundIndex()).isEqualTo(1);
        assertThat(((InvocationCompleted) events.get(9)).roundCount()).isEqualTo(2);
    }

    @Test
    void ingestAndProcess_nonRetryableLlmError_emitsInvocationFailedAndNoCompletion() {
        mockLlmProvider.respondWith(invocation -> {
            throw new LlmAuthenticationException("anthropic", "claude-sonnet-4-7", "401");
        });

        InboundMessageResult result = ingestAndProcess("Hola");

        List<OrchestrationEvent> events = recordedEvents();
        assertThat(events).hasExactlyElementsOfTypes(InvocationRequested.class,
                ContextAssembled.class, LlmInvoked.class, InvocationFailed.class);

        InvocationFailed failed = (InvocationFailed) events.get(3);
        assertThat(failed.invocationId()).isEqualTo(result.invocationId());
        assertThat(failed.failureType()).isEqualTo(InvocationFailureType.LLM_ERROR);
        assertThat(failed.detail()).contains("401");
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

    private List<OrchestrationEvent> recordedEvents() {
        return applicationEvents.stream(OrchestrationEvent.class).toList();
    }
}
