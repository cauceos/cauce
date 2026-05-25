package dev.cauce.orchestration.persistence;

import dev.cauce.orchestration.PendingInvocationStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link PendingInvocationEntity}. Result sets are filtered by
 * the pending_invocations Row-Level Security policy according to the active tenant context.
 */
public interface PendingInvocationRepository extends JpaRepository<PendingInvocationEntity, UUID> {

    List<PendingInvocationEntity> findByTenantId(UUID tenantId);

    List<PendingInvocationEntity> findByConversationId(UUID conversationId);

    List<PendingInvocationEntity> findByStatus(PendingInvocationStatus status);

    /**
     * Atomically claims up to {@code batchSize} of the oldest PENDING invocations for the
     * async worker, skipping rows already locked by a concurrent worker
     * ({@code FOR UPDATE SKIP LOCKED}). The returned rows are row-locked for the remainder
     * of the transaction, so this <strong>must</strong> run inside an open transaction and
     * the caller is expected to transition and persist each claimed row before commit.
     *
     * <p>Not yet used: the worker that drains the queue lands in a later commit. The
     * signature is defined now to fix the contract.
     */
    @Query(value = "SELECT * FROM pending_invocations WHERE status = 'PENDING' "
            + "ORDER BY created_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<PendingInvocationEntity> claimNextBatch(@Param("batchSize") int batchSize);
}
