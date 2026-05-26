package dev.cauce.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmRateLimitException;
import dev.cauce.orchestration.PendingInvocation;
import dev.cauce.orchestration.PendingInvocationService;
import dev.cauce.orchestration.service.OrchestratorService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

class PendingInvocationWorkerTest {

    private static final String WORKER_ID = "host:1:abc12345";

    private PendingInvocationService pendingInvocationService;
    private OrchestratorService orchestratorService;
    private WorkerIdentity workerIdentity;
    private TaskExecutor executor;
    private PendingInvocationWorkerProperties properties;
    private PendingInvocationWorker worker;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID triggerMessageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        pendingInvocationService = Mockito.mock(PendingInvocationService.class);
        orchestratorService = Mockito.mock(OrchestratorService.class);
        workerIdentity = Mockito.mock(WorkerIdentity.class);
        when(workerIdentity.getId()).thenReturn(WORKER_ID);
        executor = new SyncTaskExecutor();
        properties = new PendingInvocationWorkerProperties();
        properties.setBatchSize(3);
        properties.setRetryBaseIntervalSeconds(30L);
        worker = new PendingInvocationWorker(pendingInvocationService, orchestratorService,
                workerIdentity, executor, properties);
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void pollAndProcess_whenNothingClaimed_doesNotDispatch() {
        when(pendingInvocationService.claimNextBatch(WORKER_ID, 3)).thenReturn(List.of());

        worker.pollAndProcess();

        verify(orchestratorService, never()).respondToMessage(any(), any());
    }

    @Test
    void pollAndProcess_dispatchesEachClaimedInvocation() {
        UUID convA = UUID.randomUUID();
        UUID convB = UUID.randomUUID();
        UUID triggerA = UUID.randomUUID();
        UUID triggerB = UUID.randomUUID();
        PendingInvocation a = PendingInvocation.create(tenantId, convA, triggerA).claim(WORKER_ID);
        PendingInvocation b = PendingInvocation.create(tenantId, convB, triggerB).claim(WORKER_ID);
        when(pendingInvocationService.claimNextBatch(WORKER_ID, 3)).thenReturn(List.of(a, b));

        worker.pollAndProcess();

        verify(orchestratorService).respondToMessage(convA, triggerA);
        verify(orchestratorService).respondToMessage(convB, triggerB);
        verify(pendingInvocationService).markCompleted(a.id());
        verify(pendingInvocationService).markCompleted(b.id());
    }

    @Test
    void pollAndProcess_whenClaimFails_doesNotPropagateAndDoesNotProcess() {
        when(pendingInvocationService.claimNextBatch(WORKER_ID, 3))
                .thenThrow(new RuntimeException("DB outage"));

        // Must not throw — the scheduler thread keeps running.
        worker.pollAndProcess();

        verify(orchestratorService, never()).respondToMessage(any(), any());
    }

    @Test
    void processInvocation_happyPath_marksCompleted() {
        PendingInvocation claimed = claimedFirstAttempt();

        worker.processInvocation(claimed);

        verify(orchestratorService).respondToMessage(conversationId, triggerMessageId);
        verify(pendingInvocationService).markCompleted(claimed.id());
        verify(pendingInvocationService, never()).releaseForRetry(any(), anyString(), anyLong());
        verify(pendingInvocationService, never()).markFailed(any(), anyString());
        verify(pendingInvocationService, never()).markAbandoned(any(), anyString());
    }

    @Test
    void processInvocation_setsTenantContextDuringCallAndClearsItAfter() {
        PendingInvocation claimed = claimedFirstAttempt();
        AtomicReference<UUID> captured = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            captured.set(TenantContext.getCurrentTenantId().orElse(null));
            return null;
        }).when(orchestratorService).respondToMessage(any(), any());

        worker.processInvocation(claimed);

        assertThat(captured.get()).isEqualTo(tenantId);
        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    @Test
    void processInvocation_whenRetryableLlmErrorAndBudgetRemains_releasesForRetry() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new LlmRateLimitException("anthropic", "claude-sonnet-4-7", "429"))
                .when(orchestratorService).respondToMessage(any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService)
                .releaseForRetry(eq(claimed.id()), anyString(), eq(30L));
        verify(pendingInvocationService, never()).markCompleted(any());
        verify(pendingInvocationService, never()).markFailed(any(), anyString());
        verify(pendingInvocationService, never()).markAbandoned(any(), anyString());
    }

    @Test
    void processInvocation_whenNonRetryableLlmError_marksFailed() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new LlmAuthenticationException("anthropic", "claude-sonnet-4-7", "401"))
                .when(orchestratorService).respondToMessage(any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markFailed(eq(claimed.id()), anyString());
        verify(pendingInvocationService, never())
                .releaseForRetry(any(), anyString(), anyLong());
    }

    @Test
    void processInvocation_whenRetryableLlmErrorAtLastAttempt_abandons() {
        PendingInvocation claimed = claimedAtAttempt(3);
        Mockito.doThrow(new LlmRateLimitException("anthropic", "claude-sonnet-4-7", "429"))
                .when(orchestratorService).respondToMessage(any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markAbandoned(eq(claimed.id()), anyString());
        verify(pendingInvocationService, never())
                .releaseForRetry(any(), anyString(), anyLong());
        verify(pendingInvocationService, never()).markFailed(any(), anyString());
    }

    @Test
    void processInvocation_whenSetupError_marksFailed() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new AgentNotFoundException("missing"))
                .when(orchestratorService).respondToMessage(any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markFailed(eq(claimed.id()), anyString());
        verify(pendingInvocationService, never())
                .releaseForRetry(any(), anyString(), anyLong());
    }

    @Test
    void processInvocation_whenMarkingFails_doesNotPropagateAndClearsTenantContext() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new RuntimeException("DB outage"))
                .when(pendingInvocationService).markCompleted(claimed.id());

        // Must not throw — the executor thread keeps running.
        worker.processInvocation(claimed);

        verify(pendingInvocationService, times(1)).markCompleted(claimed.id());
        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    @Test
    void processInvocation_clearsTenantContextEvenAfterLlmError() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new LlmRateLimitException("anthropic", "claude-sonnet-4-7", "429"))
                .when(orchestratorService).respondToMessage(any(), any());

        worker.processInvocation(claimed);

        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    private PendingInvocation claimedFirstAttempt() {
        return PendingInvocation.create(tenantId, conversationId, triggerMessageId).claim(WORKER_ID);
    }

    private PendingInvocation claimedAtAttempt(int targetAttempt) {
        PendingInvocation invocation =
                PendingInvocation.create(tenantId, conversationId, triggerMessageId).claim(WORKER_ID);
        while (invocation.attemptCount() < targetAttempt) {
            invocation = invocation.releaseForRetry("transient", 1L).claim(WORKER_ID);
        }
        return invocation;
    }
}
