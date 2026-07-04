package dev.cauce.orchestration.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link IngestIdempotencyRecordEntity}. Result sets are filtered
 * by the ingest_idempotency_records Row-Level Security policy (visibility of the record's
 * agent) according to the active tenant context.
 *
 * <p>Writes implement the insert-first locking scheme of
 * {@link dev.cauce.orchestration.InboundMessageService}: {@link #insertLockIfAbsent} claims
 * the key at the start of the ingest work and {@link #recordResult} completes the row with
 * the outcome ids before the same transaction commits, so a committed row is always complete.
 */
public interface IngestIdempotencyRecordRepository
        extends JpaRepository<IngestIdempotencyRecordEntity, UUID> {

    Optional<IngestIdempotencyRecordEntity> findByAgentIdAndIdempotencyKey(UUID agentId,
                                                                           String idempotencyKey);

    /**
     * Claims {@code (agentId, idempotencyKey)} for the current ingest in a single race-free
     * statement. Returns {@code 1} when this call inserted the lock row (the caller owns the
     * key and proceeds with the ingest), {@code 0} when the key is already taken — the
     * {@code ingest_idempotency_records_key} unique constraint (V15) is the conflict arbiter.
     * If the conflicting row belongs to a still-uncommitted transaction, this statement blocks
     * on the unique index until that transaction resolves: on its commit this returns
     * {@code 0} and the caller re-reads the (complete) winner row; on its abort the insert
     * proceeds and this caller becomes the winner. {@code created_at} defaults to
     * {@code now()}. Runs in the caller's transaction with the tenant RLS context applied,
     * so the insert is subject to the {@code WITH CHECK} visibility policy.
     */
    @Modifying
    @Query(value = """
            INSERT INTO ingest_idempotency_records (id, agent_id, idempotency_key)
            VALUES (:id, :agentId, :idempotencyKey)
            ON CONFLICT (agent_id, idempotency_key)
            DO NOTHING
            """, nativeQuery = true)
    int insertLockIfAbsent(@Param("id") UUID id,
                           @Param("agentId") UUID agentId,
                           @Param("idempotencyKey") String idempotencyKey);

    /**
     * Completes the lock row {@code id} with the ingest outcome, in the same transaction that
     * inserted it. A single bulk UPDATE (pattern of {@code touchLastMessageAt}); subject to
     * the RLS policy in the current transaction.
     */
    @Modifying
    @Query("""
            update IngestIdempotencyRecordEntity r
            set r.conversationId = :conversationId, r.messageId = :messageId,
                r.invocationId = :invocationId
            where r.id = :id
            """)
    int recordResult(@Param("id") UUID id,
                     @Param("conversationId") UUID conversationId,
                     @Param("messageId") UUID messageId,
                     @Param("invocationId") UUID invocationId);
}
