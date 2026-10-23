package dev.cauce.orchestration.usage;

import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.audit.UsageAuditEvents;
import dev.cauce.orchestration.persistence.LlmUsageRecordMapper;
import dev.cauce.orchestration.persistence.LlmUsageRecordRepository;
import dev.cauce.orchestration.usage.UsageReport.Granularity;
import dev.cauce.orchestration.usage.UsageReport.Grouping;
import dev.cauce.orchestration.usage.UsageReport.Row;
import dev.cauce.tenancy.TenantService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the LLM usage ledger. Separate from {@link LlmUsageRecorder} on purpose: that
 * one writes a billing fact inside the orchestration loop, this one answers questions about
 * facts already written, and the two have no reason to share a lifecycle.
 *
 * <p>A {@code @Service} with {@code @Transactional} methods, so {@code RlsContextAspect}
 * establishes the RLS context from {@code TenantContext} before each read: usage rows are
 * filtered by the V19 {@code hierarchical_visibility} policy exactly like every other
 * tenant-owned row. An invocation outside the caller's hierarchy yields an empty list, which
 * is indistinguishable from one that recorded nothing — the same deliberate ambiguity the
 * invocation row itself has, and the endpoint 404s on the invocation before it gets here.
 */
@Service
public class LlmUsageQueryService {

    /** The longest range a report covers, in days, both ends included (ADR 0010, S3). */
    public static final int MAX_RANGE_DAYS = 366;

    private final LlmUsageRecordRepository repository;
    private final LlmUsageRecordMapper mapper;
    private final TenantService tenantService;
    private final AuditEventRecorder auditRecorder;
    private final Clock clock;

    public LlmUsageQueryService(LlmUsageRecordRepository repository, LlmUsageRecordMapper mapper,
                                TenantService tenantService, AuditEventRecorder auditRecorder, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.tenantService = tenantService;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    /**
     * Every provider call recorded for one invocation, oldest first. Empty when the
     * invocation recorded none — which is a real outcome, not an error: usage is written
     * only after a provider actually responds, so a call that failed on the way out leaves
     * no row.
     */
    @Transactional(readOnly = true)
    public List<LlmUsageRecord> findByInvocation(UUID invocationId) {
        return repository.findByInvocationIdOrderByCreatedAtAscIdAsc(invocationId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    /**
     * The usage of {@code tenantId} and its descendants (ADR 0010, S3), per {@code granularity} and
     * {@code grouping}, from {@code from} to {@code to} inclusive, days and months cut in
     * {@code zone}. A null {@code from} is the first day of the current month in {@code zone}, a
     * null {@code to} today there, both by the database clock. A tenant reading another tenant's
     * usage leaves {@code admin.usage.read} in the chain of the tenant read (S4); reading its own
     * leaves nothing. Not read-only: the audit record commits with the read.
     *
     * @throws dev.cauce.core.tenant.TenantNotFoundException if the tenant is not visible
     * @throws IllegalArgumentException if the zone is not a region id, the range is reversed, or
     *     longer than {@link #MAX_RANGE_DAYS}
     */
    @Transactional
    public UsageReport usage(UUID tenantId, LocalDate from, LocalDate to, Granularity granularity, Grouping grouping,
                             String zone) {
        Objects.requireNonNull(granularity, "granularity");
        Objects.requireNonNull(grouping, "grouping");
        ZoneId zoneId = regionZone(zone);
        LocalDate today = LocalDate.ofInstant(clock.instant(), zoneId);
        LocalDate start = from == null ? today.withDayOfMonth(1) : from;
        LocalDate end = to == null ? today : to;
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("'to' (" + end + ") is before 'from' (" + start + ")");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("The range covers more than " + MAX_RANGE_DAYS + " days");
        }
        tenantService.getTenant(tenantId); // 404 when not visible, before anything is read

        List<Row> byAgent = repository.aggregate(tenantId, granularity == Granularity.DAY ? "day" : "month",
                zoneId.getId(), start, end).stream().map(r -> row(r, granularity)).toList();
        List<Row> rows = grouping == Grouping.AGENT ? byAgent : byTenant(byAgent);

        UUID actorId = TenantContext.getCurrentTenantId().orElseThrow();
        UsageReport report = new UsageReport(tenantId, start, end, granularity, grouping, zoneId, rows);
        if (!actorId.equals(tenantId)) {
            auditRecorder.record(UsageAuditEvents.usageRead(report, actorId));
        }
        return report;
    }

    /** A zone the database knows by the same name: a region id or UTC, never a raw offset. */
    private static ZoneId regionZone(String zone) {
        if (zone == null || !ZoneId.getAvailableZoneIds().contains(zone)) {
            throw new IllegalArgumentException("Unknown time zone '" + zone + "': use a region id such as "
                    + "Europe/Madrid, or UTC");
        }
        return ZoneId.of(zone);
    }

    /** The rows of each period and tenant, summed over its agents (they arrive ordered by both). */
    private static List<Row> byTenant(List<Row> byAgent) {
        Map<String, Row> merged = new LinkedHashMap<>();
        for (Row row : byAgent) {
            Row tenantRow = new Row(row.period(), row.tenantId(), null, row.inputTokens(), row.cacheReadTokens(),
                    row.cacheWriteTokens(), row.cacheWriteLongTokens(), row.outputTokens(), row.calls(),
                    row.conversations(), row.costUsd(), row.unpricedCalls());
            merged.merge(row.period() + "/" + row.tenantId(), tenantRow, Row::plus);
        }
        return new ArrayList<>(merged.values());
    }

    private static Row row(Object[] r, Granularity granularity) {
        String day = (String) r[0];
        String period = granularity == Granularity.MONTH ? day.substring(0, 7) : day;
        return new Row(period, (UUID) r[1], (UUID) r[2], number(r[3]), number(r[4]), number(r[5]), number(r[6]),
                number(r[7]), number(r[8]), number(r[9]), (BigDecimal) r[10], number(r[11]));
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
