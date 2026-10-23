package dev.cauce.orchestration.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for LLM usage rows: the finder the invocation-status endpoint needs, and
 * the aggregation of the usage endpoint over the V46 cost view.
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

    /**
     * The usage of {@code root} and its descendants from {@code from} to {@code to} (inclusive dates
     * in {@code zone}), per period ({@code unit}: {@code day} or {@code month}, cut in {@code zone}),
     * tenant and agent, in that order. Each row: period ({@code yyyy-MM-dd}, the period's first
     * day), tenant id, agent id, input, cache read, cache write, long-retention cache write and output tokens, calls,
     * conversations, cost of the priced calls (null when none was priced) and unpriced calls.
     * Descendants are found through {@code tenants} under RLS, so a tenant outside the caller's
     * hierarchy contributes nothing even if it were named.
     */
    @Query(value = """
            SELECT to_char(date_trunc(:unit, c.created_at AT TIME ZONE :zone), 'YYYY-MM-DD') AS period,
                   c.tenant_id,
                   c.agent_id,
                   sum(c.input_tokens),
                   sum(c.cache_read_tokens),
                   sum(c.cache_write_tokens),
                   sum(c.cache_write_long_tokens),
                   sum(c.output_tokens),
                   count(*),
                   count(DISTINCT c.conversation_id),
                   sum(c.cost_usd),
                   count(*) FILTER (WHERE c.cost_usd IS NULL)
            FROM llm_usage_costs c
            JOIN tenants t ON t.id = c.tenant_id
            WHERE (t.id = :root
                   OR t.parent_tenant_id = :root
                   OR t.parent_tenant_id IN (SELECT p.id FROM tenants p WHERE p.parent_tenant_id = :root))
              AND c.created_at >= CAST(:from AS timestamp) AT TIME ZONE :zone
              AND c.created_at < CAST(CAST(:to AS date) + 1 AS timestamp) AT TIME ZONE :zone
            GROUP BY 1, 2, 3
            ORDER BY 1, 2, 3
            """, nativeQuery = true)
    List<Object[]> aggregate(@Param("root") UUID root, @Param("unit") String unit, @Param("zone") String zone,
                             @Param("from") LocalDate from, @Param("to") LocalDate to);
}
