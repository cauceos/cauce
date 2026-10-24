package dev.cauce.orchestration.worker;

import dev.cauce.core.LogKeys;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.llm.exception.LlmParameterRejectedException;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.orchestration.LogSafe;
import dev.cauce.orchestration.OrchestrationConfig;
import dev.cauce.orchestration.PendingInvocation;
import dev.cauce.orchestration.PendingInvocationService;
import dev.cauce.orchestration.events.InvocationFailed;
import dev.cauce.orchestration.events.InvocationFailureType;
import dev.cauce.orchestration.exception.EmptyReplyException;
import dev.cauce.orchestration.exception.LlmProviderNotAvailableException;
import dev.cauce.orchestration.exception.MaxToolIterationsExceededException;
import dev.cauce.orchestration.exception.PromptDoesNotFitContextException;
import dev.cauce.orchestration.exception.TranscriptionNotAvailableException;
import dev.cauce.orchestration.exception.SpendLimitReachedException;
import dev.cauce.orchestration.exception.UnpricedModelException;
import dev.cauce.orchestration.exception.VoiceNoteRejectedException;
import dev.cauce.orchestration.exception.VoiceNoteUnavailableException;
import dev.cauce.orchestration.service.FailureFallbackService;
import dev.cauce.orchestration.service.OrchestratorService;
import dev.cauce.orchestration.tracing.MessageTracing;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asynchronous worker that drains the {@code pending_invocations} queue.
 *
 * <p>Each tick (driven by {@code @Scheduled} fixed delay) the worker:
 * <ol>
 *   <li>Claims up to {@code batch-size} oldest PENDING rows whose backoff has cleared via
 *       {@link PendingInvocationService#claimNextBatch} (one transaction). The claim runs
 *       across all tenants and is annotated {@code @NoTenantContext} downstream.</li>
 *   <li>Dispatches each claimed row to the dedicated executor; the LLM call (which can
 *       take seconds) runs OFF the claim transaction.</li>
 *   <li>For each row, sets {@code TenantContext} to the row's owning tenant before
 *       invoking {@link OrchestratorService#respondToMessage}, classifies any error via
 *       {@link LlmErrorClassifier}, and persists the appropriate terminal/retry transition
 *       through {@link PendingInvocationService}. {@code TenantContext} is cleared in a
 *       {@code finally} block.</li>
 * </ol>
 *
 * <p>The worker is fire-and-forget: the next poll runs independently of in-flight tasks.
 * Backpressure comes from the executor's {@code CallerRunsPolicy} — if the pool and its
 * queue are saturated, the scheduler thread runs the task inline, throttling subsequent
 * polls naturally.
 *
 * <p>Disabled by {@code cauce.orchestration.worker.enabled=false} (e.g. in integration
 * tests that need to drive {@link #pollAndProcess()} manually).
 */
@Component
@ConditionalOnProperty(prefix = "cauce.orchestration.worker", name = "enabled", matchIfMissing = true)
public class PendingInvocationWorker {

    private static final Logger log = LoggerFactory.getLogger(PendingInvocationWorker.class);

    private final PendingInvocationService pendingInvocationService;
    private final OrchestratorService orchestratorService;
    private final WorkerIdentity workerIdentity;
    private final TaskExecutor executor;
    private final PendingInvocationWorkerProperties properties;
    private final MessageTracing tracing;
    private final ApplicationEventPublisher eventPublisher;
    private final FailureFallbackService failureFallbackService;

    public PendingInvocationWorker(PendingInvocationService pendingInvocationService,
                                   OrchestratorService orchestratorService,
                                   WorkerIdentity workerIdentity,
                                   @Qualifier(OrchestrationConfig.WORKER_EXECUTOR_BEAN)
                                           TaskExecutor executor,
                                   PendingInvocationWorkerProperties properties,
                                   ApplicationEventPublisher eventPublisher,
                                   FailureFallbackService failureFallbackService,
                                   MessageTracing tracing) {
        this.pendingInvocationService = pendingInvocationService;
        this.orchestratorService = orchestratorService;
        this.workerIdentity = workerIdentity;
        this.executor = executor;
        this.properties = properties;
        this.tracing = tracing;
        this.eventPublisher = eventPublisher;
        this.failureFallbackService = failureFallbackService;
    }

    /**
     * Single polling tick: claim a batch and dispatch each row to the executor. Public so
     * integration tests can drive it deterministically without waiting for the scheduler.
     */
    @Scheduled(fixedDelayString = "${cauce.orchestration.worker.poll-interval-ms:1000}")
    public void pollAndProcess() {
        List<PendingInvocation> claimed;
        try {
            claimed = pendingInvocationService.claimNextBatch(
                    workerIdentity.getId(), properties.getBatchSize());
        } catch (RuntimeException e) {
            // A claim failure must not kill the scheduler thread; the next tick retries.
            log.warn("Worker {} failed to claim a batch: {}", workerIdentity.getId(), LogSafe.describe(e));
            log.debug("Claim failure detail", e);
            return;
        }
        if (claimed.isEmpty()) {
            return;
        }
        log.debug("Worker {} claimed {} invocation(s)", workerIdentity.getId(), claimed.size());
        for (PendingInvocation invocation : claimed) {
            executor.execute(() -> processInvocation(invocation));
        }
    }

    /**
     * Processes a single previously-claimed invocation. NOT transactional itself — the
     * tenant-scoped service calls inside open their own short transactions, so the LLM
     * call (potentially several seconds) is not held in any open transaction.
     *
     * <p>Public to make tests easy: they may pass a hand-constructed claimed invocation
     * instead of going through {@link #pollAndProcess()}.
     */
    public void processInvocation(PendingInvocation invocation) {
        TenantContext.setCurrentTenantId(invocation.tenantId());
        // ADR 0011, L1: every log line of this invocation carries its ids.
        MDC.put(LogKeys.TENANT_ID, invocation.tenantId().toString());
        MDC.put(LogKeys.INVOCATION_ID, invocation.id().toString());
        MDC.put(LogKeys.CONVERSATION_ID, invocation.conversationId().toString());
        // ADR 0011, T2: the span of this invocation continues the trace of the message's ingest.
        MessageTracing.TracedSpan span = tracing.continueTrace(invocation.traceParent().orElse(null), "cauce.invocation")
                .tag("tenant_id", invocation.tenantId().toString())
                .tag("invocation_id", invocation.id().toString())
                .tag("conversation_id", invocation.conversationId().toString())
                .tag("message_id", invocation.triggerMessageId().toString());
        try {
            try {
                orchestratorService.respondToMessage(invocation.id(),
                        invocation.conversationId(), invocation.triggerMessageId());
                span.tag("outcome", "completed");
                pendingInvocationService.markCompleted(invocation.id());
                log.debug("Worker {} completed invocation {}",
                        workerIdentity.getId(), invocation.id());
            } catch (LlmProviderException e) {
                span.error(e);
                handleLlmFailure(invocation, e);
            } catch (VoiceNoteUnavailableException e) {
                span.error(e);
                handleVoiceNoteUnavailable(invocation, e);
            } catch (RuntimeException e) {
                span.error(e);
                span.tag("failure_type", failureTypeOf(e).name());
                // Setup errors (conversation/agent/message not found, invalid trigger,
                // provider not configured, unknown model, context too large, etc.) are
                // not transient — failing the row is the correct action.
                log.warn("Worker {} failing invocation {} due to non-LLM error: {}",
                        workerIdentity.getId(), invocation.id(), LogSafe.describe(e));
                log.debug("Invocation failure detail", e);
                InvocationFailureType failureType = failureTypeOf(e);
                safeMark(() -> {
                    pendingInvocationService.markFailed(invocation.id(), e.toString(), failureType);
                    publishFailed(invocation, failureType, e.toString());
                }, invocation, "FAILED");
            }
        } finally {
            span.close();
            TenantContext.clear();
            MDC.remove(LogKeys.TENANT_ID);
            MDC.remove(LogKeys.INVOCATION_ID);
            MDC.remove(LogKeys.CONVERSATION_ID);
            MDC.remove(LogKeys.AGENT_ID);
        }
    }

    private static InvocationFailureType failureTypeOf(RuntimeException e) {
        if (e instanceof MaxToolIterationsExceededException) {
            return InvocationFailureType.MAX_TOOL_ITERATIONS;
        }
        if (e instanceof EmptyReplyException) {
            return InvocationFailureType.EMPTY_REPLY;
        }
        if (e instanceof SpendLimitReachedException) {
            return InvocationFailureType.SPEND_LIMIT_REACHED;
        }
        if (e instanceof LlmProviderNotAvailableException) {
            // The agent names a provider this instance has not configured: setup, not an outage.
            return InvocationFailureType.AGENT_CONFIGURATION;
        }
        if (e instanceof VoiceNoteRejectedException) {
            // ADR 0012, V5: the person is asked to write it, or to send a shorter one.
            return InvocationFailureType.VOICE_NOTE_REJECTED;
        }
        if (e instanceof TranscriptionNotAvailableException) {
            // The instance cannot transcribe: its setup, not a provider outage.
            return InvocationFailureType.AGENT_CONFIGURATION;
        }
        if (e instanceof PromptDoesNotFitContextException) {
            // The agent's prompt and tools exceed the window it is served: its setup, never a cut.
            return InvocationFailureType.AGENT_CONFIGURATION;
        }
        if (e instanceof UnpricedModelException) {
            // ADR 0010, S7: a limit that cannot price the agent's model is the agent's configuration to fix.
            return InvocationFailureType.AGENT_CONFIGURATION;
        }
        return InvocationFailureType.SETUP_ERROR;
    }

    private void handleLlmFailure(PendingInvocation invocation, LlmProviderException error) {
        String errorSummary = error.toString(); // stored with the invocation, never logged (ADR 0011, L2)
        String logged = LogSafe.describe(error);
        if (!LlmErrorClassifier.isRetryable(error)) {
            log.warn("Worker {} failing invocation {} due to non-retryable LLM error: {}",
                    workerIdentity.getId(), invocation.id(), logged);
            // A model rejecting what the agent configures is the agent's configuration to fix.
            InvocationFailureType type = error instanceof LlmParameterRejectedException
                    ? InvocationFailureType.AGENT_CONFIGURATION
                    : InvocationFailureType.LLM_ERROR;
            safeMark(() -> {
                pendingInvocationService.markFailed(invocation.id(), errorSummary, type);
                publishFailed(invocation, type, errorSummary);
            }, invocation, "FAILED");
            return;
        }
        releaseOrAbandon(invocation, errorSummary, logged, InvocationFailureType.LLM_RETRIES_EXHAUSTED);
    }

    /**
     * The channel did not hand over a voice note (ADR 0012): retried with backoff when transient,
     * like a transient model error, and each attempt fetches it anew; when permanent, the person is
     * asked to write it, as for a note that could not be read.
     */
    private void handleVoiceNoteUnavailable(PendingInvocation invocation, VoiceNoteUnavailableException error) {
        String errorSummary = error.toString(); // stored with the invocation, never logged (ADR 0011, L2)
        String logged = LogSafe.describe(error);
        if (!error.isTransient()) {
            log.warn("Worker {} failing invocation {}: the voice note cannot be fetched: {}",
                    workerIdentity.getId(), invocation.id(), logged);
            safeMark(() -> {
                pendingInvocationService.markFailed(invocation.id(), errorSummary,
                        InvocationFailureType.VOICE_NOTE_REJECTED);
                publishFailed(invocation, InvocationFailureType.VOICE_NOTE_REJECTED, errorSummary);
            }, invocation, "FAILED");
            return;
        }
        releaseOrAbandon(invocation, errorSummary, logged, InvocationFailureType.MEDIA_RETRIES_EXHAUSTED);
    }

    /**
     * A transient failure: released for retry with backoff, or abandoned as {@code exhausted} with
     * the fallback reply once the attempt budget is spent.
     */
    private void releaseOrAbandon(PendingInvocation invocation, String errorSummary, String logged,
                                  InvocationFailureType exhausted) {
        // The claim() already incremented attemptCount, so attemptCount == attempts consumed so
        // far. If we have used up the budget, abandon; otherwise release with backoff.
        if (invocation.attemptCount() >= invocation.maxAttempts()) {
            log.warn("Worker {} abandoning invocation {} after exhausting {} attempt(s): {}",
                    workerIdentity.getId(), invocation.id(), invocation.maxAttempts(), logged);
            safeMark(() -> {
                pendingInvocationService.markAbandoned(invocation.id(), errorSummary, exhausted);
                publishFailed(invocation, exhausted, errorSummary);
            }, invocation, "ABANDONED");
            return;
        }
        log.info("Worker {} releasing invocation {} for retry (attempt {}/{}): {}",
                workerIdentity.getId(), invocation.id(), invocation.attemptCount(),
                invocation.maxAttempts(), logged);
        safeMark(() -> pendingInvocationService.releaseForRetry(
                        invocation.id(), errorSummary, properties.getRetryBaseIntervalSeconds()),
                invocation, "RELEASED");
    }

    /**
     * Publishes the permanent-failure event. Called inside the {@link #safeMark} action, right
     * after the terminal transition returns (its short transaction committed), so no event
     * fires for a mark that failed — the reaper will recover the row and the event comes from
     * the real retry.
     */
    private void publishFailed(PendingInvocation invocation, InvocationFailureType failureType,
                               String detail) {
        eventPublisher.publishEvent(
                new InvocationFailed(invocation.id(), failureType, detail, Instant.now()));
        // ADR 0005, C3: the terminal transition committed, so the interlocutor gets the fallback.
        failureFallbackService.sendFallback(invocation.id(), invocation.conversationId(), failureType);
    }

    private void safeMark(Runnable action, PendingInvocation invocation, String label) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // If marking the row fails (DB outage, lost RLS context, etc.) the reaper
            // will eventually pick the row up as orphaned. Log loudly and move on.
            log.error("Worker {} failed to mark invocation {} as {}: {}",
                    workerIdentity.getId(), invocation.id(), label, LogSafe.describe(e));
            log.debug("Mark failure detail", e);
        }
    }
}
