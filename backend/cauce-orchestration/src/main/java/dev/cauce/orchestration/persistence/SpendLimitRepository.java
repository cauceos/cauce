package dev.cauce.orchestration.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for spend limits, plus what the spend check needs beyond a limit row: the
 * month's spend, whether a model has a price in force, and the once-a-month alert. Every query runs
 * under the V46, V47 and V48 policies.
 */
public interface SpendLimitRepository extends JpaRepository<SpendLimitEntity, UUID> {

    Optional<SpendLimitEntity> findByTenantId(UUID tenantId);

    /**
     * What {@code tenantId} spent in the current calendar month in UTC, by the database clock: one
     * row, the cost of its priced calls (0 when none) and the number of unpriced ones.
     */
    @Query(value = """
            SELECT coalesce(sum(c.cost_usd), 0), count(*) FILTER (WHERE c.cost_usd IS NULL)
            FROM llm_usage_costs c
            WHERE c.tenant_id = :tenantId
              AND c.created_at >= date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
            """, nativeQuery = true)
    List<Object[]> spentThisMonth(@Param("tenantId") UUID tenantId);

    /**
     * Whether {@code provider}'s {@code model} has a price in force now, by the database clock, and,
     * when {@code longWrites}, whether that price (the latest in force) prices long-retention cache
     * writes: without it they are unpriced (V51).
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM (SELECT cache_write_long_usd_per_mtok FROM llm_prices "
            + "WHERE provider = :provider AND model = :model AND valid_from <= now() "
            + "ORDER BY valid_from DESC, id DESC LIMIT 1) latest "
            + "WHERE NOT :longWrites OR latest.cache_write_long_usd_per_mtok IS NOT NULL)", nativeQuery = true)
    boolean hasPriceInForce(@Param("provider") String provider, @Param("model") String model,
                            @Param("longWrites") boolean longWrites);

    /**
     * Whether {@code provider}'s {@code model} has a price in force now, by the database clock, that
     * prices a minute of audio (V56): a transcriber's price.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM (SELECT audio_usd_per_minute FROM llm_prices "
            + "WHERE provider = :provider AND model = :model AND valid_from <= now() "
            + "ORDER BY valid_from DESC, id DESC LIMIT 1) latest "
            + "WHERE latest.audio_usd_per_minute IS NOT NULL)", nativeQuery = true)
    boolean hasAudioPriceInForce(@Param("provider") String provider, @Param("model") String model);

    /**
     * Records that {@code tenantId} reached {@code thresholdPercent} of its limit in the month that
     * starts on {@code period}, unless it already had: 1 when this call recorded it, 0 otherwise.
     */
    @Modifying
    @Query(value = "INSERT INTO spend_alerts (id, tenant_id, period, threshold_percent, monthly_limit_usd, spent_usd) "
            + "VALUES (:id, :tenantId, :period, :thresholdPercent, :limitUsd, :spentUsd) "
            + "ON CONFLICT ON CONSTRAINT spend_alerts_once DO NOTHING", nativeQuery = true)
    int recordAlertOnce(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("period") LocalDate period,
                        @Param("thresholdPercent") int thresholdPercent, @Param("limitUsd") BigDecimal limitUsd,
                        @Param("spentUsd") BigDecimal spentUsd);
}
