package dev.cauce.orchestration.persistence;

import dev.cauce.orchestration.PendingInvocationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link PendingInvocationEntity}. Result sets are filtered by
 * the pending_invocations Row-Level Security policy according to the active tenant context.
 *
 * <p>The worker-facing operations ({@link #claimNextBatch}, {@link #findOrphanedSince})
 * cross all tenants and must run without an established {@code TenantContext}; the calling
 * service annotates them {@code @NoTenantContext}. Today the application connects as a
 * superuser and RLS is bypassed there. When the application is later wired to the
 * least-privilege {@code cauce_app} role, these queries will need a different mechanism
 * (a worker-only role with {@code BYPASSRLS}, or {@code SECURITY DEFINER} functions) —
 * the {@code @NoTenantContext} marker stays; the DB-level path changes.
 */
public interface PendingInvocationRepository extends JpaRepository<PendingInvocationEntity, UUID> {

    List<PendingInvocationEntity> findByTenantId(UUID tenantId);

    List<PendingInvocationEntity> findByConversationId(UUID conversationId);

    List<PendingInvocationEntity> findByStatus(PendingInvocationStatus status);

    /**
     * Atomically claims up to {@code batchSize} of the oldest PENDING invocations whose
     * backoff has cleared ({@code next_attempt_at IS NULL} or {@code <= NOW()}), skipping
     * rows already locked by a concurrent worker ({@code FOR UPDATE SKIP LOCKED}).
     *
     * <p>Backed by the partial index {@code idx_pending_invocations_pending_ready}. The
     * returned rows are row-locked for the remainder of the transaction, so this
     * <strong>must</strong> run inside an open transaction and the caller is expected to
     * transition and persist each claimed row before commit.
     */
    @Query(value = "SELECT * FROM pending_invocations "
            + "WHERE status = 'PENDING' "
            + "  AND (next_attempt_at IS NULL OR next_attempt_at <= NOW()) "
            + "ORDER BY created_at ASC "
            + "LIMIT :batchSize FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<PendingInvocationEntity> claimNextBatch(@Param("batchSize") int batchSize);

    /**
     * Returns invocations in {@link PendingInvocationStatus#PROCESSING} whose claim is
     * older than {@code threshold}. Used by the reaper to recover work whose worker died
     * (or hung) before transitioning the row to a terminal or PENDING state.
     */
    @Query("SELECT p FROM PendingInvocationEntity p "
            + "WHERE p.status = dev.cauce.orchestration.PendingInvocationStatus.PROCESSING "
            + "  AND p.claimedAt < :threshold")
    List<PendingInvocationEntity> findOrphanedSince(@Param("threshold") Instant threshold);
}
