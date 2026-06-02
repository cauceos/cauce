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
 * service annotates them {@code @NoTenantContext}. Under the least-privilege {@code cauce_app}
 * role they would otherwise be fail-closed by RLS, so they go through the owner-owned
 * {@code SECURITY DEFINER} functions {@code claim_pending_invocations} and
 * {@code orphaned_invocations} (migration V12), the narrow cross-tenant escape hatch (see
 * ADR 0001). The caller then sets the tenant context and does all further work under RLS.
 */
public interface PendingInvocationRepository extends JpaRepository<PendingInvocationEntity, UUID> {

    List<PendingInvocationEntity> findByTenantId(UUID tenantId);

    List<PendingInvocationEntity> findByConversationId(UUID conversationId);

    List<PendingInvocationEntity> findByStatus(PendingInvocationStatus status);

    /**
     * Atomically claims up to {@code batchSize} of the oldest PENDING invocations whose
     * backoff has cleared, marks them PROCESSING under {@code workerId}, and returns the
     * claimed rows — all in one statement via the {@code claim_pending_invocations}
     * SECURITY DEFINER function (V12). The function uses {@code FOR UPDATE SKIP LOCKED} so
     * concurrent workers claim disjoint sets, and runs as the owner so the cross-tenant
     * claim is not fail-closed under {@code cauce_app}. The returned rows are already
     * PROCESSING; the caller only maps them.
     */
    @Query(value = "SELECT * FROM claim_pending_invocations(:workerId, :batchSize)",
            nativeQuery = true)
    List<PendingInvocationEntity> claimNextBatch(@Param("workerId") String workerId,
                                                 @Param("batchSize") int batchSize);

    /**
     * Returns invocations in {@link PendingInvocationStatus#PROCESSING} whose claim is older
     * than {@code threshold}, via the {@code orphaned_invocations} SECURITY DEFINER function
     * (V12) — the cross-tenant discovery the reaper needs without being fail-closed under
     * {@code cauce_app}. Discovery only: the recovery transition stays in the domain under
     * RLS in each row's tenant context.
     */
    @Query(value = "SELECT * FROM orphaned_invocations(:threshold)", nativeQuery = true)
    List<PendingInvocationEntity> findOrphanedSince(@Param("threshold") Instant threshold);
}
