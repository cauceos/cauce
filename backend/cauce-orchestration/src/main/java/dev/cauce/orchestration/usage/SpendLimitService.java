package dev.cauce.orchestration.usage;

import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tenant.Tier;
import dev.cauce.orchestration.audit.UsageAuditEvents;
import dev.cauce.orchestration.persistence.SpendLimitMapper;
import dev.cauce.orchestration.persistence.SpendLimitRepository;
import dev.cauce.orchestration.usage.SpendLimit.SpendState;
import dev.cauce.tenancy.TenantService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The monthly spend limit of a client (ADR 0010, S5): set, changed and removed only by a strict
 * ancestor, read by the client and its ancestors. Every change is audited in the client's chain.
 * The V48 policies enforce the same rule underneath: this service is the readable refusal, not the
 * only one.
 */
@Service
public class SpendLimitService {

    private final SpendLimitRepository repository;
    private final SpendLimitMapper mapper;
    private final TenantService tenantService;
    private final AuditEventRecorder auditRecorder;
    private final Clock clock;

    public SpendLimitService(SpendLimitRepository repository, SpendLimitMapper mapper, TenantService tenantService,
                             AuditEventRecorder auditRecorder, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.tenantService = tenantService;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    /**
     * Sets the limit of {@code tenantId} to {@code monthlyLimitUsd} US$ a month, in force at once.
     *
     * @throws dev.cauce.core.tenant.TenantNotFoundException if the tenant is not visible
     * @throws InvalidTenantTierException if the tenant is not a CLIENT
     * @throws OwnSpendLimitException if the caller is the tenant itself
     * @throws IllegalArgumentException if the amount is not positive or has more than two decimals
     */
    @Transactional
    public SpendStatus setLimit(UUID tenantId, BigDecimal monthlyLimitUsd) {
        UUID actorId = requireManagedBy(tenantId);
        BigDecimal amount = cents(monthlyLimitUsd);
        Optional<SpendLimit> previous = repository.findByTenantId(tenantId).map(mapper::toDomain);
        SpendLimit limit = previous
                .map(existing -> existing.changedTo(amount, actorId, clock.instant()))
                .orElseGet(() -> SpendLimit.create(tenantId, amount, actorId, clock.instant()));
        repository.save(mapper.toEntity(limit));
        auditRecorder.record(UsageAuditEvents.spendLimitSet(limit, previous.map(SpendLimit::monthlyLimitUsd)));
        return status(tenantId, Optional.of(limit));
    }

    /**
     * Removes the limit of {@code tenantId}; nothing happens, and nothing is audited, when it had none.
     *
     * @throws dev.cauce.core.tenant.TenantNotFoundException if the tenant is not visible
     * @throws InvalidTenantTierException if the tenant is not a CLIENT
     * @throws OwnSpendLimitException if the caller is the tenant itself
     */
    @Transactional
    public void removeLimit(UUID tenantId) {
        UUID actorId = requireManagedBy(tenantId);
        repository.findByTenantId(tenantId).ifPresent(entity -> {
            repository.delete(entity);
            auditRecorder.record(UsageAuditEvents.spendLimitRemoved(tenantId, entity.getMonthlyLimitUsd(), actorId));
        });
    }

    /**
     * The limit of {@code tenantId}, if any, and what it spent this month (UTC) against it.
     *
     * @throws dev.cauce.core.tenant.TenantNotFoundException if the tenant is not visible
     */
    @Transactional(readOnly = true)
    public SpendStatus status(UUID tenantId) {
        tenantService.getTenant(tenantId);
        return status(tenantId, repository.findByTenantId(tenantId).map(mapper::toDomain));
    }

    private SpendStatus status(UUID tenantId, Optional<SpendLimit> limit) {
        Object[] spent = spentThisMonth(repository, tenantId);
        BigDecimal spentUsd = (BigDecimal) spent[0];
        long unpriced = ((Number) spent[1]).longValue();
        SpendState state = limit.map(l -> l.stateOf(spentUsd)).orElse(SpendState.NONE);
        return new SpendStatus(tenantId, limit.map(SpendLimit::monthlyLimitUsd).orElse(null),
                currentMonth(clock), spentUsd, unpriced, state);
    }

    /** The visible CLIENT {@code tenantId}, which the caller manages: returns the caller. */
    private UUID requireManagedBy(UUID tenantId) {
        UUID actorId = TenantContext.getCurrentTenantId().orElseThrow();
        Tenant tenant = tenantService.getTenant(tenantId);
        if (tenant.tier() != Tier.CLIENT) {
            throw new InvalidTenantTierException("A spend limit is set on a CLIENT, but " + tenantId + " is "
                    + tenant.tier());
        }
        if (actorId.equals(tenantId)) {
            throw new OwnSpendLimitException("A tenant's spend limit is set by its partner or operator, never by "
                    + "the tenant itself");
        }
        return actorId;
    }

    private static BigDecimal cents(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("The monthly limit must be a positive amount of US$");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("The monthly limit has at most two decimals: " + amount, e);
        }
    }

    /** The calendar month in UTC of the clock's reading: the database's (ADR 0008). */
    static YearMonth currentMonth(Clock clock) {
        return YearMonth.from(clock.instant().atOffset(ZoneOffset.UTC));
    }

    /** {@code [spent US$, unpriced calls]} of {@code tenantId} this month, by the database clock. */
    static Object[] spentThisMonth(SpendLimitRepository repository, UUID tenantId) {
        List<Object[]> rows = repository.spentThisMonth(tenantId);
        return rows.get(0);
    }
}
