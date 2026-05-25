package dev.cauce.orchestration;

import dev.cauce.core.UuidGenerator;
import dev.cauce.orchestration.exception.InvalidPendingInvocationTransitionException;
import dev.cauce.orchestration.exception.MaxRetriesExceededException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single LLM invocation queued for the asynchronous orchestrator. Each USER message that
 * needs an agent reply enqueues one; a worker (later commit) claims PENDING rows with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} and drives them through the lifecycle.
 *
 * <p>Pure domain type: no persistence or framework dependencies. Immutable; created via
 * {@link #create} with status {@link PendingInvocationStatus#PENDING}, a zero attempt count
 * and a time-ordered UUIDv7 id. The lifecycle transitions ({@link #claim},
 * {@link #complete}, {@link #releaseForRetry}, {@link #fail}, {@link #abandon}) do not
 * mutate the instance: each returns a new {@code PendingInvocation} with the updated state,
 * or throws {@link InvalidPendingInvocationTransitionException} if the transition is not
 * allowed from the current status.
 *
 * <p>{@code tenantId} is the owning (CLIENT) tenant of the conversation's agent, not the
 * tenant that happens to enqueue the work: it is what the hierarchical Row-Level Security
 * policy keys on, so the owning client and its partner/operator can all see the row.
 *
 * <p>The queue is deliberately loosely coupled to conversations and messages:
 * {@code conversationId} and {@code triggerMessageId} are plain ids, not enforced foreign
 * keys. If the target disappears before processing, the worker resolves it and marks the
 * row FAILED rather than the database forbidding the delete.
 */
public final class PendingInvocation {

    /** Default attempt budget before a retry loop gives up and the row is ABANDONED. */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** Upper bound for stored error messages; longer values are truncated. */
    private static final int ERROR_MAX_LENGTH = 1000;

    private final UUID id;
    private final UUID tenantId;
    private final UUID conversationId;
    private final UUID triggerMessageId;
    private final PendingInvocationStatus status;
    private final int attemptCount;
    private final int maxAttempts;
    private final Instant lastAttemptAt; // null until first claimed
    private final String lastError;      // null until a failure/retry records one
    private final Instant createdAt;
    private final Instant claimedAt;     // null unless currently PROCESSING
    private final String claimedBy;      // null unless currently PROCESSING
    private final Instant completedAt;   // null unless COMPLETED, FAILED or ABANDONED

    private PendingInvocation(UUID id, UUID tenantId, UUID conversationId, UUID triggerMessageId,
                             PendingInvocationStatus status, int attemptCount, int maxAttempts,
                             Instant lastAttemptAt, String lastError, Instant createdAt,
                             Instant claimedAt, String claimedBy, Instant completedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.conversationId = conversationId;
        this.triggerMessageId = triggerMessageId;
        this.status = status;
        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
        this.lastAttemptAt = lastAttemptAt;
        this.lastError = lastError;
        this.createdAt = createdAt;
        this.claimedAt = claimedAt;
        this.claimedBy = claimedBy;
        this.completedAt = completedAt;
    }

    // === FACTORY METHODS ===

    /**
     * Enqueues a new invocation for {@code conversationId}, triggered by
     * {@code triggerMessageId} and owned by {@code tenantId}. Starts
     * {@link PendingInvocationStatus#PENDING} with a zero attempt count and the default
     * attempt budget.
     */
    public static PendingInvocation create(UUID tenantId, UUID conversationId, UUID triggerMessageId) {
        return new PendingInvocation(
                UuidGenerator.newV7(),
                Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(conversationId, "conversationId"),
                Objects.requireNonNull(triggerMessageId, "triggerMessageId"),
                PendingInvocationStatus.PENDING,
                0,
                DEFAULT_MAX_ATTEMPTS,
                null,
                null,
                Instant.now(),
                null,
                null,
                null);
    }

    /** Rebuilds an invocation from already-persisted state. For the persistence layer only. */
    public static PendingInvocation rehydrate(UUID id, UUID tenantId, UUID conversationId,
                                             UUID triggerMessageId, PendingInvocationStatus status,
                                             int attemptCount, int maxAttempts, Instant lastAttemptAt,
                                             String lastError, Instant createdAt, Instant claimedAt,
                                             String claimedBy, Instant completedAt) {
        return new PendingInvocation(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(conversationId, "conversationId"),
                Objects.requireNonNull(triggerMessageId, "triggerMessageId"),
                Objects.requireNonNull(status, "status"),
                attemptCount,
                maxAttempts,
                lastAttemptAt, // nullable
                lastError,     // nullable
                Objects.requireNonNull(createdAt, "createdAt"),
                claimedAt,     // nullable
                claimedBy,     // nullable
                completedAt);  // nullable
    }

    // === LIFECYCLE TRANSITIONS (IMMUTABLE - RETURN NEW INSTANCES) ===

    /**
     * Claims this invocation for processing by {@code workerId}. Allowed only from
     * {@link PendingInvocationStatus#PENDING}. Moves to {@link PendingInvocationStatus#PROCESSING},
     * increments the attempt count, and records the worker and the attempt instant.
     *
     * @return a new PROCESSING invocation
     * @throws InvalidPendingInvocationTransitionException if not PENDING
     */
    public PendingInvocation claim(String workerId) {
        if (status != PendingInvocationStatus.PENDING) {
            throw new InvalidPendingInvocationTransitionException(
                    transitionError(PendingInvocationStatus.PROCESSING));
        }
        String worker = requireText(workerId, "workerId");
        Instant now = Instant.now();
        return new PendingInvocation(id, tenantId, conversationId, triggerMessageId,
                PendingInvocationStatus.PROCESSING, attemptCount + 1, maxAttempts, now, lastError,
                createdAt, now, worker, completedAt);
    }

    /**
     * Marks a successful processing run. Allowed only from
     * {@link PendingInvocationStatus#PROCESSING}.
     *
     * @return a new COMPLETED invocation with {@code completedAt} set to now
     * @throws InvalidPendingInvocationTransitionException if not PROCESSING
     */
    public PendingInvocation complete() {
        if (status != PendingInvocationStatus.PROCESSING) {
            throw new InvalidPendingInvocationTransitionException(
                    transitionError(PendingInvocationStatus.COMPLETED));
        }
        return new PendingInvocation(id, tenantId, conversationId, triggerMessageId,
                PendingInvocationStatus.COMPLETED, attemptCount, maxAttempts, lastAttemptAt, lastError,
                createdAt, claimedAt, claimedBy, Instant.now());
    }

    /**
     * Releases the invocation back to {@link PendingInvocationStatus#PENDING} for another
     * attempt (e.g. a transient provider error). Allowed only from
     * {@link PendingInvocationStatus#PROCESSING}. Clears the claim and records the error.
     *
     * @param errorMessage the reason for the retry; may be {@code null}, truncated to 1000 chars
     * @return a new PENDING invocation with the claim cleared
     * @throws InvalidPendingInvocationTransitionException if not PROCESSING
     * @throws MaxRetriesExceededException if the attempt budget is already exhausted; the
     *     caller must use {@link #fail(String)} or {@link #abandon(String)} instead
     */
    public PendingInvocation releaseForRetry(String errorMessage) {
        if (status != PendingInvocationStatus.PROCESSING) {
            throw new InvalidPendingInvocationTransitionException(
                    transitionError(PendingInvocationStatus.PENDING));
        }
        if (attemptCount >= maxAttempts) {
            throw new MaxRetriesExceededException(
                    "PendingInvocation %s exhausted its %d attempt(s); use fail() or abandon()"
                            .formatted(id, maxAttempts));
        }
        return new PendingInvocation(id, tenantId, conversationId, triggerMessageId,
                PendingInvocationStatus.PENDING, attemptCount, maxAttempts, lastAttemptAt,
                truncateError(errorMessage), createdAt, null, null, completedAt);
    }

    /**
     * Marks an unrecoverable failure (e.g. HTTP 401/400). Allowed only from
     * {@link PendingInvocationStatus#PROCESSING}.
     *
     * @param errorMessage the failure reason; must not be blank, truncated to 1000 chars
     * @return a new FAILED invocation with {@code completedAt} set to now
     * @throws InvalidPendingInvocationTransitionException if not PROCESSING
     */
    public PendingInvocation fail(String errorMessage) {
        if (status != PendingInvocationStatus.PROCESSING) {
            throw new InvalidPendingInvocationTransitionException(
                    transitionError(PendingInvocationStatus.FAILED));
        }
        return new PendingInvocation(id, tenantId, conversationId, triggerMessageId,
                PendingInvocationStatus.FAILED, attemptCount, maxAttempts, lastAttemptAt,
                truncateError(requireText(errorMessage, "errorMessage")), createdAt, claimedAt,
                claimedBy, Instant.now());
    }

    /**
     * Gives up after exhausting the attempt budget. Allowed only from
     * {@link PendingInvocationStatus#PROCESSING}.
     *
     * @param errorMessage the abandonment reason; must not be blank, truncated to 1000 chars
     * @return a new ABANDONED invocation with {@code completedAt} set to now
     * @throws InvalidPendingInvocationTransitionException if not PROCESSING
     */
    public PendingInvocation abandon(String errorMessage) {
        if (status != PendingInvocationStatus.PROCESSING) {
            throw new InvalidPendingInvocationTransitionException(
                    transitionError(PendingInvocationStatus.ABANDONED));
        }
        return new PendingInvocation(id, tenantId, conversationId, triggerMessageId,
                PendingInvocationStatus.ABANDONED, attemptCount, maxAttempts, lastAttemptAt,
                truncateError(requireText(errorMessage, "errorMessage")), createdAt, claimedAt,
                claimedBy, Instant.now());
    }

    private String transitionError(PendingInvocationStatus target) {
        return "Cannot transition PendingInvocation %s from %s to %s".formatted(id, status, target);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private static String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= ERROR_MAX_LENGTH ? error : error.substring(0, ERROR_MAX_LENGTH);
    }

    // === ACCESSORS ===

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID conversationId() {
        return conversationId;
    }

    public UUID triggerMessageId() {
        return triggerMessageId;
    }

    public PendingInvocationStatus status() {
        return status;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Instant lastAttemptAt() {
        return lastAttemptAt;
    }

    public String lastError() {
        return lastError;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant claimedAt() {
        return claimedAt;
    }

    public String claimedBy() {
        return claimedBy;
    }

    public Instant completedAt() {
        return completedAt;
    }

    // === EQUALS/HASHCODE (ID-BASED) ===

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof PendingInvocation other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        // lastError is intentionally omitted (it may be large or carry provider detail).
        return "PendingInvocation[id=%s, tenantId=%s, status=%s, attemptCount=%d/%d]"
                .formatted(id, tenantId, status, attemptCount, maxAttempts);
    }
}
