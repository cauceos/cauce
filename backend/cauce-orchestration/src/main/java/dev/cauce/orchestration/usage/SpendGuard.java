package dev.cauce.orchestration.usage;

import dev.cauce.core.UuidGenerator;
import dev.cauce.core.agent.Agent;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.usage.SpendAlertNotifier;
import dev.cauce.core.usage.SpendAlertNotifier.SpendAlert;
import dev.cauce.orchestration.audit.UsageAuditEvents;
import dev.cauce.orchestration.persistence.SpendLimitMapper;
import dev.cauce.orchestration.persistence.SpendLimitRepository;
import dev.cauce.orchestration.usage.SpendLimit.SpendState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The spend check before each invocation (ADR 0010, S6, S7). Without a limit, everything runs.
 * With one, an agent whose model has no price in force is refused, and otherwise the client's spend
 * this month (UTC) is compared with its limit: at 80 % the owner is warned and the invocation runs;
 * at 100 % the invocation is refused. Each threshold is reported once per tenant and month: only the
 * check whose insert creates the {@code spend_alerts} row tells the owner (through
 * {@link SpendAlertNotifier}) and records {@code conduct.spend.threshold_reached}, in this same
 * transaction.
 *
 * <p>Runs once per invocation, before the first model call, under the invocation's tenant context:
 * an invocation that started is never cut halfway through its tools, so the limit is soft by the
 * invocations of one client running at the same time.
 */
@Service
public class SpendGuard {

    private static final Logger log = LoggerFactory.getLogger(SpendGuard.class);

    private final SpendLimitRepository repository;
    private final SpendLimitMapper mapper;
    private final AuditEventRecorder auditRecorder;
    private final ObjectProvider<SpendAlertNotifier> notifier;
    private final Clock clock;

    public SpendGuard(SpendLimitRepository repository, SpendLimitMapper mapper, AuditEventRecorder auditRecorder,
                      ObjectProvider<SpendAlertNotifier> notifier, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.auditRecorder = auditRecorder;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** What the check says about one invocation of {@code agent}. */
    public enum Verdict {
        /** No limit, or under it: the invocation runs. */
        ALLOWED,
        /** The client spent its limit this month: the invocation fails with the fallback reply. */
        LIMIT_REACHED,
        /** The client has a limit and the agent's model has no price in force: a misconfiguration. */
        UNPRICED_MODEL
    }

    @Transactional
    public Verdict check(Agent agent) {
        Optional<SpendLimit> limit = repository.findByTenantId(agent.tenantId()).map(mapper::toDomain);
        if (limit.isEmpty()) {
            return Verdict.ALLOWED;
        }
        if (!repository.hasPriceInForce(agent.modelProvider(), agent.modelName())) {
            return Verdict.UNPRICED_MODEL;
        }
        BigDecimal spent = (BigDecimal) SpendLimitService.spentThisMonth(repository, agent.tenantId())[0];
        SpendState state = limit.get().stateOf(spent);
        if (state == SpendState.REACHED) {
            alertOnce(limit.get(), spent, 100);
            return Verdict.LIMIT_REACHED;
        }
        if (state == SpendState.WARNING) {
            alertOnce(limit.get(), spent, 80);
        }
        return Verdict.ALLOWED;
    }

    private void alertOnce(SpendLimit limit, BigDecimal spent, int thresholdPercent) {
        YearMonth period = SpendLimitService.currentMonth(clock);
        UUID alertId = UuidGenerator.newV7();
        if (repository.recordAlertOnce(alertId, limit.tenantId(), period.atDay(1), thresholdPercent,
                limit.monthlyLimitUsd(), spent) == 0) {
            return; // already reported this month
        }
        SpendAlert alert = new SpendAlert(alertId, limit.tenantId(), period, thresholdPercent, limit.monthlyLimitUsd(),
                spent);
        auditRecorder.record(UsageAuditEvents.spendThresholdReached(alert));
        SpendAlertNotifier owner = notifier.getIfAvailable();
        if (owner == null) {
            log.warn("Tenant {} reached {} % of its spend limit in {}, and no owner notifier is deployed",
                    limit.tenantId(), thresholdPercent, period);
            return;
        }
        owner.thresholdReached(alert);
    }
}
