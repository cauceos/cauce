package dev.cauce.api.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The instance's state the operator is alerted on, as gauges (ADR 0011, M2): the invocation queue,
 * the audit outbox, owner notices not sent, spend alerts and each client's spend this month. Read
 * every {@code cauce.observability.gauges.interval-ms} (a minute by default) through the two
 * aggregate-only {@code SECURITY DEFINER} functions of V53, never row by row.
 *
 * <p>A pure reader: a failed read is logged and leaves the last values in place, so a database
 * hiccup never fails anything else. Off under the {@code test} profile, where it would race the
 * ITs' {@code TRUNCATE}.
 */
@Component
@ConditionalOnProperty(name = "cauce.observability.gauges.enabled", havingValue = "true", matchIfMissing = true)
public class OperationalGauges {

    private static final Logger log = LoggerFactory.getLogger(OperationalGauges.class);

    static final String PENDING = "cauce.queue.pending";
    static final String OLDEST_DUE = "cauce.queue.oldest_due.age";
    static final String OUTBOX_PENDING = "cauce.audit.outbox.pending";
    static final String OUTBOX_OLDEST = "cauce.audit.outbox.oldest.age";
    static final String NOTICES_UNSENT = "cauce.owner.notices.unsent";
    static final String SPEND_ALERTS = "cauce.spend.alerts";
    static final String MONTH_SPEND = "cauce.spend.month";

    private final JdbcTemplate jdbc;
    private final Map<String, Double> values = new ConcurrentHashMap<>();
    private final MultiGauge monthSpend;

    public OperationalGauges(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        gauge(registry, PENDING, Tags.empty(), "invocations", "Invocations waiting in the queue");
        gauge(registry, OLDEST_DUE, Tags.empty(), "seconds", "Age of the oldest due invocation not yet claimed");
        gauge(registry, OUTBOX_PENDING, Tags.empty(), "events", "Audit events not yet written into the chain");
        gauge(registry, OUTBOX_OLDEST, Tags.empty(), "seconds", "Age of the oldest audit event not yet chained");
        gauge(registry, NOTICES_UNSENT, Tags.empty(), "notices", "Owner notices not sent in the last 24 hours");
        gauge(registry, SPEND_ALERTS, Tags.of("threshold", "80"), "clients", "Clients past 80% of their limit");
        gauge(registry, SPEND_ALERTS, Tags.of("threshold", "100"), "clients", "Clients that reached their limit");
        this.monthSpend = MultiGauge.builder(MONTH_SPEND).baseUnit("usd")
                .description("Spend of each client this month (UTC)").register(registry);
    }

    private void gauge(MeterRegistry registry, String name, Tags tags, String unit, String description) {
        String key = key(name, tags);
        values.put(key, 0.0);
        Gauge.builder(name, values, v -> v.getOrDefault(key, 0.0))
                .tags(tags).baseUnit(unit).description(description).register(registry);
    }

    private static String key(String name, Tags tags) {
        return name + tags;
    }

    /** Reads the snapshot and the month's spend; on failure the gauges keep their last values. */
    @Scheduled(initialDelayString = "${cauce.observability.gauges.interval-ms:60000}",
            fixedDelayString = "${cauce.observability.gauges.interval-ms:60000}")
    public void refresh() {
        try {
            jdbc.query("SELECT * FROM operational_snapshot()", rs -> {
                values.put(key(PENDING, Tags.empty()), rs.getDouble("pending_invocations"));
                values.put(key(OLDEST_DUE, Tags.empty()), rs.getDouble("oldest_due_pending_seconds"));
                values.put(key(OUTBOX_PENDING, Tags.empty()), rs.getDouble("audit_outbox_pending"));
                values.put(key(OUTBOX_OLDEST, Tags.empty()), rs.getDouble("oldest_audit_outbox_seconds"));
                values.put(key(NOTICES_UNSENT, Tags.empty()), rs.getDouble("owner_notices_unsent_24h"));
                values.put(key(SPEND_ALERTS, Tags.of("threshold", "80")), rs.getDouble("spend_alerts_80_month"));
                values.put(key(SPEND_ALERTS, Tags.of("threshold", "100")), rs.getDouble("spend_alerts_100_month"));
            });
            List<MultiGauge.Row<?>> rows = jdbc.query("SELECT tenant_id, spent_usd FROM month_spend_by_tenant()",
                    (rs, i) -> MultiGauge.Row.of(Tags.of("tenant_id", rs.getString("tenant_id")),
                            rs.getBigDecimal("spent_usd").doubleValue()));
            monthSpend.register(rows, true);
        } catch (RuntimeException e) {
            log.warn("Could not read the operational snapshot: {}", e.getClass().getSimpleName());
            log.debug("Operational snapshot failure detail", e);
        }
    }
}
