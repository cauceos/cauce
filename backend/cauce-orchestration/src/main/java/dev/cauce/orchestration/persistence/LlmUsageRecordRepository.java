package dev.cauce.orchestration.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for LLM usage rows. Writes are the ledger's reason to exist; the
 * aggregation surface (per tenant, per period) is still a separate future unit, so the only
 * finder here is the one the invocation-status endpoint needs.
 *
 * <p>Every query runs under the {@code hierarchical_visibility} policy of V19, so a caller
 * only ever sees usage belonging to tenants it can see.
 */
public interface LlmUsageRecordRepository extends JpaRepository<LlmUsageRecordEntity, UUID> {

    /**
     * The provider calls made for one invocation, oldest first.
     *
     * <p>Ordered by {@code created_at} and not by {@code round_index}: a worker retry re-runs
     * the loop from round 0, so {@code round_index} repeats within an invocation and does not
     * order — or identify — a row. Ties break on {@code id} (UUIDv7, so monotonic) to keep the
     * order total.
     */
    List<LlmUsageRecordEntity> findByInvocationIdOrderByCreatedAtAscIdAsc(UUID invocationId);
}
