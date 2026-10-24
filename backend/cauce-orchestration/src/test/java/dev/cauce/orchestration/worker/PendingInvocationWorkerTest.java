package dev.cauce.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmParameterRejectedException;
import dev.cauce.llm.exception.LlmRateLimitException;
import dev.cauce.orchestration.PendingInvocation;
import dev.cauce.orchestration.PendingInvocationService;
import dev.cauce.orchestration.events.InvocationFailed;
import dev.cauce.orchestration.events.InvocationFailureType;
import dev.cauce.orchestration.exception.EmptyReplyException;
import dev.cauce.orchestration.exception.LlmProviderNotAvailableException;
import dev.cauce.orchestration.exception.MaxToolIterationsExceededException;
import dev.cauce.orchestration.exception.SpendLimitReachedException;
import dev.cauce.orchestration.exception.PromptDoesNotFitContextException;
import dev.cauce.orchestration.exception.UnpricedModelException;
import dev.cauce.orchestration.exception.VoiceNoteUnavailableException;
import dev.cauce.orchestration.service.FailureFallbackService;
import dev.cauce.orchestration.service.OrchestratorService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

class PendingInvocationWorkerTest {

    private FailureFallbackService failureFallbackService;

    private static final String WORKER_ID = "host:1:abc12345";

    private PendingInvocationService pendingInvocationService;
    private OrchestratorService orchestratorService;
    private WorkerIdentity workerIdentity;
    private TaskExecutor executor;
    private PendingInvocationWorkerProperties properties;
    private ApplicationEventPublisher eventPublisher;
    private PendingInvocationWorker worker;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID triggerMessageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        failureFallbackService = Mockito.mock(FailureFallbackService.class);
        pendingInvocationService = Mockito.mock(PendingInvocationService.class);
        orchestratorService = Mockito.mock(OrchestratorService.class);
        workerIdentity = Mockito.mock(WorkerIdentity.class);
        when(workerIdentity.getId()).thenReturn(WORKER_ID);
        executor = new SyncTaskExecutor();
        properties = new PendingInvocationWorkerProperties();
        properties.setBatchSize(3);
        properties.setRetryBaseIntervalSeconds(30L);
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        worker = new PendingInvocationWorker(pendingInvocationService, orchestratorService,
                workerIdentity, executor, properties, eventPublisher, failureFallbackService, dev.cauce.orchestration.tracing.MessageTracing.noop());
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

        verify(orchestratorService, never()).respondToMessage(any(), any(), any());
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

        verify(orchestratorService).respondToMessage(a.id(), convA, triggerA);
        verify(orchestratorService).respondToMessage(b.id(), convB, triggerB);
        verify(pendingInvocationService).markCompleted(a.id());
        verify(pendingInvocationService).markCompleted(b.id());
    }

    @Test
    void pollAndProcess_whenClaimFails_doesNotPropagateAndDoesNotProcess() {
        when(pendingInvocationService.claimNextBatch(WORKER_ID, 3))
                .thenThrow(new RuntimeException("DB outage"));

        // Must not throw — the scheduler thread keeps running.
        worker.pollAndProcess();

        verify(orchestratorService, never()).respondToMessage(any(), any(), any());
    }

    @Test
    void processInvocation_putsTheInvocationsIdsInTheLoggingContext_andTakesThemOutAfter() {
        PendingInvocation claimed = claimedFirstAttempt();
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        Mockito.doAnswer(call -> {
            seen.putAll(org.slf4j.MDC.getCopyOfContextMap());
            return null;
        }).when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        assertThat(seen).containsEntry(dev.cauce.core.LogKeys.INVOCATION_ID, claimed.id().toString())
                .containsEntry(dev.cauce.core.LogKeys.TENANT_ID, claimed.tenantId().toString())
                .containsEntry(dev.cauce.core.LogKeys.CONVERSATION_ID, claimed.conversationId().toString());
        assertThat(org.slf4j.MDC.get(dev.cauce.core.LogKeys.INVOCATION_ID)).isNull();
        assertThat(org.slf4j.MDC.get(dev.cauce.core.LogKeys.TENANT_ID)).isNull();
    }

    @Test
    void processInvocation_whenTheModelRejectsTheAgentsParameter_failsAsAConfigurationError_withoutRetry() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new LlmParameterRejectedException("anthropic", "claude-sonnet-5-5", "temperature",
                        "HTTP 400: temperature is deprecated for this model"))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markFailed(eq(claimed.id()), anyString(),
                eq(InvocationFailureType.AGENT_CONFIGURATION));
        verify(pendingInvocationService, never()).releaseForRetry(any(), anyString(), anyLong());
    }

    @Test
    void processInvocation_happyPath_marksCompleted() {
        PendingInvocation claimed = claimedFirstAttempt();

        worker.processInvocation(claimed);

        verify(orchestratorService).respondToMessage(claimed.id(), conversationId, triggerMessageId);
        verify(pendingInvocationService).markCompleted(claimed.id());
        verify(pendingInvocationService, never()).releaseForRetry(any(), anyString(), anyLong());
        verify(pendingInvocationService, never()).markFailed(any(), anyString(), any());
        verify(pendingInvocationService, never()).markAbandoned(any(), anyString(), any());
        // ADR 0005, C3: a failure that will be retried sends no fallback.
        verifyNoInteractions(failureFallbackService);
    }

    @Test
    void processInvocation_setsTenantContextDuringCallAndClearsItAfter() {
        PendingInvocation claimed = claimedFirstAttempt();
        AtomicReference<UUID> captured = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            captured.set(TenantContext.getCurrentTenantId().orElse(null));
            return null;
        }).when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        assertThat(captured.get()).isEqualTo(tenantId);
        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    @Test
    void processInvocation_whenRetryableLlmErrorAndBudgetRemains_releasesForRetry() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new LlmRateLimitException("anthropic", "claude-sonnet-4-7", "429"))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService)
                .releaseForRetry(eq(claimed.id()), anyString(), eq(30L));
        verify(pendingInvocationService, never()).markCompleted(any());
        verify(pendingInvocationService, never()).markFailed(any(), anyString(), any());
        verify(pendingInvocationService, never()).markAbandoned(any(), anyString(), any());
    }

    @Test
    void processInvocation_whenNonRetryableLlmError_marksFailed() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new LlmAuthenticationException("anthropic", "claude-sonnet-4-7", "401"))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markFailed(
                eq(claimed.id()), anyString(), eq(InvocationFailureType.LLM_ERROR));
        verify(pendingInvocationService, never())
                .releaseForRetry(any(), anyString(), anyLong());
        assertThat(publishedFailure().failureType()).isEqualTo(InvocationFailureType.LLM_ERROR);
        verify(failureFallbackService).sendFallback(claimed.id(), claimed.conversationId(), InvocationFailureType.LLM_ERROR);
    }

    @Test
    void processInvocation_whenRetryableLlmErrorAtLastAttempt_abandons() {
        PendingInvocation claimed = claimedAtAttempt(3);
        Mockito.doThrow(new LlmRateLimitException("anthropic", "claude-sonnet-4-7", "429"))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markAbandoned(
                eq(claimed.id()), anyString(), eq(InvocationFailureType.LLM_RETRIES_EXHAUSTED));
        verify(pendingInvocationService, never())
                .releaseForRetry(any(), anyString(), anyLong());
        verify(pendingInvocationService, never()).markFailed(any(), anyString(), any());
        assertThat(publishedFailure().failureType())
                .isEqualTo(InvocationFailureType.LLM_RETRIES_EXHAUSTED);
        verify(failureFallbackService).sendFallback(claimed.id(), claimed.conversationId(),
                InvocationFailureType.LLM_RETRIES_EXHAUSTED);
    }

    @Test
    void processInvocation_whenTheVoiceNoteIsTransientlyUnavailable_releasesForRetryWithBackoff() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new VoiceNoteUnavailableException("Telegram file download returned HTTP 502", true, null))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).releaseForRetry(eq(claimed.id()), anyString(), eq(30L));
        verify(pendingInvocationService, never()).markFailed(any(), anyString(), any());
        verify(failureFallbackService, never()).sendFallback(any(), any(), any());
    }

    @Test
    void processInvocation_whenTheVoiceNoteStaysUnavailable_abandonsWithTheFallbackReply() {
        PendingInvocation claimed = claimedAtAttempt(3);
        Mockito.doThrow(new VoiceNoteUnavailableException("WhatsApp media download returned HTTP 503", true, null))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markAbandoned(
                eq(claimed.id()), anyString(), eq(InvocationFailureType.MEDIA_RETRIES_EXHAUSTED));
        verify(pendingInvocationService, never()).releaseForRetry(any(), anyString(), anyLong());
        verify(failureFallbackService).sendFallback(claimed.id(), claimed.conversationId(),
                InvocationFailureType.MEDIA_RETRIES_EXHAUSTED);
    }

    @Test
    void processInvocation_whenTheChannelNoLongerKnowsTheVoiceNote_failsAsUnreadable_withoutRetry() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new VoiceNoteUnavailableException("Telegram getFile returned HTTP 400", false, null))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markFailed(
                eq(claimed.id()), anyString(), eq(InvocationFailureType.VOICE_NOTE_REJECTED));
        verify(pendingInvocationService, never()).releaseForRetry(any(), anyString(), anyLong());
        verify(failureFallbackService).sendFallback(claimed.id(), claimed.conversationId(),
                InvocationFailureType.VOICE_NOTE_REJECTED);
    }

    @Test
    void processInvocation_whenSetupError_marksFailed() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new AgentNotFoundException("missing"))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markFailed(
                eq(claimed.id()), anyString(), eq(InvocationFailureType.SETUP_ERROR));
        verify(pendingInvocationService, never())
                .releaseForRetry(any(), anyString(), anyLong());
        InvocationFailed failed = publishedFailure();
        assertThat(failed.failureType()).isEqualTo(InvocationFailureType.SETUP_ERROR);
        assertThat(failed.invocationId()).isEqualTo(claimed.id());
        assertThat(failed.detail()).contains("missing");
    }

    @Test
    void processInvocation_whenIterationCapExceeded_marksFailedAsMaxToolIterations() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new MaxToolIterationsExceededException("cap"))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markFailed(
                eq(claimed.id()), anyString(), eq(InvocationFailureType.MAX_TOOL_ITERATIONS));
        assertThat(publishedFailure().failureType())
                .isEqualTo(InvocationFailureType.MAX_TOOL_ITERATIONS);
    }

    @Test
    void processInvocation_whenTheSpendLimitIsReached_marksFailed_andSendsTheFallback() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new SpendLimitReachedException("limit"))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markFailed(
                eq(claimed.id()), anyString(), eq(InvocationFailureType.SPEND_LIMIT_REACHED));
        verify(pendingInvocationService, never()).releaseForRetry(any(), anyString(), anyLong());
        verify(failureFallbackService).sendFallback(claimed.id(), claimed.conversationId(),
                InvocationFailureType.SPEND_LIMIT_REACHED);
    }

    @Test
    void processInvocation_whenTheModelHasNoPriceUnderALimit_marksFailedAsAgentConfiguration() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new UnpricedModelException("unpriced"))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markFailed(
                eq(claimed.id()), anyString(), eq(InvocationFailureType.AGENT_CONFIGURATION));
        verify(failureFallbackService).sendFallback(claimed.id(), claimed.conversationId(),
                InvocationFailureType.AGENT_CONFIGURATION);
    }

    @Test
    void processInvocation_whenThePromptDoesNotFitTheWindow_marksFailedAsAgentConfiguration() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new PromptDoesNotFitContextException("The system prompt and tools do not fit"))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markFailed(
                eq(claimed.id()), anyString(), eq(InvocationFailureType.AGENT_CONFIGURATION));
        verify(pendingInvocationService, never()).releaseForRetry(any(), anyString(), anyLong());
        verify(failureFallbackService).sendFallback(claimed.id(), claimed.conversationId(),
                InvocationFailureType.AGENT_CONFIGURATION);
    }

    @Test
    void processInvocation_whenTheVoiceNoteCannotBeRead_failsAsVoiceNoteRejected_withItsFallback() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new dev.cauce.orchestration.exception.VoiceNoteRejectedException("too long"))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markFailed(
                eq(claimed.id()), anyString(), eq(InvocationFailureType.VOICE_NOTE_REJECTED));
        verify(failureFallbackService).sendFallback(claimed.id(), claimed.conversationId(),
                InvocationFailureType.VOICE_NOTE_REJECTED);
    }

    @Test
    void processInvocation_whenTheInstanceCannotTranscribe_marksFailedAsAgentConfiguration() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new dev.cauce.orchestration.exception.TranscriptionNotAvailableException("no transcriber"))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markFailed(
                eq(claimed.id()), anyString(), eq(InvocationFailureType.AGENT_CONFIGURATION));
    }

    @Test
    void processInvocation_whenTheProviderIsNotConfigured_marksFailedAsAgentConfiguration() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new LlmProviderNotAvailableException("LLM provider 'anthropic' is not configured"))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        // Setup, not an outage: never SETUP_ERROR (INTERNAL_ERROR) nor a provider failure.
        verify(pendingInvocationService).markFailed(
                eq(claimed.id()), anyString(), eq(InvocationFailureType.AGENT_CONFIGURATION));
        verify(pendingInvocationService, never()).markAbandoned(any(), anyString(), any());
        verify(pendingInvocationService, never()).releaseForRetry(any(), anyString(), anyLong());
    }

    @Test
    void processInvocation_whenReplyIsEmpty_marksFailedAsEmptyReply() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new EmptyReplyException("empty"))
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        verify(pendingInvocationService).markFailed(
                eq(claimed.id()), anyString(), eq(InvocationFailureType.EMPTY_REPLY));
        verify(pendingInvocationService, never()).releaseForRetry(any(), anyString(), anyLong());
        assertThat(publishedFailure().failureType()).isEqualTo(InvocationFailureType.EMPTY_REPLY);
        verify(failureFallbackService).sendFallback(claimed.id(), claimed.conversationId(), InvocationFailureType.EMPTY_REPLY);
    }

    @Test
    void processInvocation_happyPathOrRetryRelease_publishesNoFailureEvent() {
        worker.processInvocation(claimedFirstAttempt());
        Mockito.doThrow(new LlmRateLimitException("anthropic", "claude-sonnet-4-7", "429"))
                .when(orchestratorService).respondToMessage(any(), any(), any());
        worker.processInvocation(claimedFirstAttempt());

        // Completion is not a failure and a retry release is not permanent: nothing published.
        verify(eventPublisher, never()).publishEvent(any(InvocationFailed.class));
    }

    @Test
    void processInvocation_whenMarkFailedItselfFails_publishesNoFailureEvent() {
        PendingInvocation claimed = claimedFirstAttempt();
        Mockito.doThrow(new LlmAuthenticationException("anthropic", "claude-sonnet-4-7", "401"))
                .when(orchestratorService).respondToMessage(any(), any(), any());
        Mockito.doThrow(new RuntimeException("DB outage"))
                .when(pendingInvocationService).markFailed(any(), anyString(), any());

        worker.processInvocation(claimed);

        // The terminal transition did not commit; the reaper will recover the row, so no
        // event may claim the invocation permanently failed.
        verify(eventPublisher, never()).publishEvent(any(InvocationFailed.class));
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
                .when(orchestratorService).respondToMessage(any(), any(), any());

        worker.processInvocation(claimed);

        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    private InvocationFailed publishedFailure() {
        ArgumentCaptor<InvocationFailed> captor = ArgumentCaptor.forClass(InvocationFailed.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    private PendingInvocation claimedFirstAttempt() {
        return PendingInvocation.create(tenantId, conversationId, triggerMessageId).claim(WORKER_ID);
    }

    private PendingInvocation claimedAtAttempt(int targetAttempt) {
        PendingInvocation invocation =
                PendingInvocation.create(tenantId, conversationId, triggerMessageId).claim(WORKER_ID);
        while (invocation.attemptCount() < targetAttempt) {
            invocation = invocation.releaseForRetry("transient", 1L, Instant.now()).claim(WORKER_ID);
        }
        return invocation;
    }
}
