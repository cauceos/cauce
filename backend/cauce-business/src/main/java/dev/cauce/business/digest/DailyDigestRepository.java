package dev.cauce.business.digest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for daily digests: RLS-filtered like every other, the insert-first claim
 * of a day, and the scheduler's cross-tenant read through the V38 {@code SECURITY DEFINER}
 * function (ADR 0001).
 */
public interface DailyDigestRepository extends JpaRepository<DailyDigestEntity, UUID> {

    Optional<DailyDigestEntity> findFirstByTenantIdOrderByLocalDateDesc(UUID tenantId);

    Optional<DailyDigestEntity> findByTenantIdAndLocalDate(UUID tenantId, LocalDate localDate);

    List<DailyDigestEntity> findByTenantIdOrderByIdAsc(UUID tenantId, Limit limit);

    List<DailyDigestEntity> findByTenantIdAndIdGreaterThanOrderByIdAsc(UUID tenantId, UUID afterId, Limit limit);

    /**
     * Records the digest of {@code (tenantId, localDate)} unless one exists: returns 1 when this call
     * wrote it, 0 when another run already had. A concurrent claim blocks on the unique constraint
     * until the first commits, then returns 0. Runs in the caller's transaction under RLS.
     */
    @Modifying
    @Query(value = """
            INSERT INTO daily_digests (id, tenant_id, local_date, covered_from, covered_to, booking_count,
                                       handoff_count, question_count, content, notified, created_at)
            VALUES (:id, :tenantId, :localDate, :coveredFrom, :coveredTo, :bookingCount, :handoffCount,
                    :questionCount, :content, :notified, :createdAt)
            ON CONFLICT ON CONSTRAINT daily_digests_day_key DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("localDate") LocalDate localDate,
                       @Param("coveredFrom") Instant coveredFrom, @Param("coveredTo") Instant coveredTo,
                       @Param("bookingCount") int bookingCount, @Param("handoffCount") int handoffCount,
                       @Param("questionCount") int questionCount, @Param("content") String content,
                       @Param("notified") boolean notified, @Param("createdAt") Instant createdAt);

    /** A business that wants a digest: its tenant, time zone and local time. */
    interface Candidate {
        UUID getTenantId();

        String getTimeZone();

        LocalTime getDailyDigestTime();
    }

    @Query(value = "SELECT tenant_id AS tenantId, time_zone AS timeZone, daily_digest_time AS dailyDigestTime "
            + "FROM daily_digest_candidates()", nativeQuery = true)
    List<Candidate> candidates();
}
