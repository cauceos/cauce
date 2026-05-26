package dev.cauce.orchestration.worker;

import dev.cauce.core.tenant.TenantContext;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.orchestration.OrchestrationConfig;
import dev.cauce.orchestration.PendingInvocation;
import dev.cauce.orchestration.PendingInvocationService;
import dev.cauce.orchestration.service.OrchestratorService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    public PendingInvocationWorker(PendingInvocationService pendingInvocationService,
                                   OrchestratorService orchestratorService,
                                   WorkerIdentity workerIdentity,
                                   @Qualifier(OrchestrationConfig.WORKER_EXECUTOR_BEAN)
                                           TaskExecutor executor,
                                   PendingInvocationWorkerProperties properties) {
        this.pendingInvocationService = pendingInvocationService;
        this.orchestratorService = orchestratorService;
        this.workerIdentity = workerIdentity;
        this.executor = executor;
        this.properties = properties;
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
            log.warn("Worker {} failed to claim a batch: {}", workerIdentity.getId(), e.toString(), e);
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
        try {
            try {
                orchestratorService.respondToMessage(
                        invocation.conversationId(), invocation.triggerMessageId());
                pendingInvocationService.markCompleted(invocation.id());
                log.debug("Worker {} completed invocation {}",
                        workerIdentity.getId(), invocation.id());
            } catch (LlmProviderException e) {
                handleLlmFailure(invocation, e);
            } catch (RuntimeException e) {
                // Setup errors (conversation/agent/message not found, invalid trigger,
                // provider not configured, unknown model, context too large, etc.) are
                // not transient — failing the row is the correct action.
                log.warn("Worker {} failing invocation {} due to non-LLM error: {}",
                        workerIdentity.getId(), invocation.id(), e.toString(), e);
                safeMark(() -> pendingInvocationService.markFailed(invocation.id(), e.toString()),
                        invocation, "FAILED");
            }
        } finally {
            TenantContext.clear();
        }
    }

    private void handleLlmFailure(PendingInvocation invocation, LlmProviderException error) {
        String errorSummary = error.toString();
        if (!LlmErrorClassifier.isRetryable(error)) {
            log.warn("Worker {} failing invocation {} due to non-retryable LLM error: {}",
                    workerIdentity.getId(), invocation.id(), errorSummary);
            safeMark(() -> pendingInvocationService.markFailed(invocation.id(), errorSummary),
                    invocation, "FAILED");
            return;
        }
        // Retryable. The claim() already incremented attemptCount, so attemptCount ==
        // attempts consumed so far. If we have used up the budget, abandon; otherwise
        // release with backoff.
        if (invocation.attemptCount() >= invocation.maxAttempts()) {
            log.warn("Worker {} abandoning invocation {} after exhausting {} attempt(s): {}",
                    workerIdentity.getId(), invocation.id(), invocation.maxAttempts(), errorSummary);
            safeMark(() -> pendingInvocationService.markAbandoned(invocation.id(), errorSummary),
                    invocation, "ABANDONED");
            return;
        }
        log.info("Worker {} releasing invocation {} for retry (attempt {}/{}): {}",
                workerIdentity.getId(), invocation.id(), invocation.attemptCount(),
                invocation.maxAttempts(), errorSummary);
        safeMark(() -> pendingInvocationService.releaseForRetry(
                        invocation.id(), errorSummary, properties.getRetryBaseIntervalSeconds()),
                invocation, "RELEASED");
    }

    private void safeMark(Runnable action, PendingInvocation invocation, String label) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // If marking the row fails (DB outage, lost RLS context, etc.) the reaper
            // will eventually pick the row up as orphaned. Log loudly and move on.
            log.error("Worker {} failed to mark invocation {} as {}: {}",
                    workerIdentity.getId(), invocation.id(), label, e.toString(), e);
        }
    }
}
