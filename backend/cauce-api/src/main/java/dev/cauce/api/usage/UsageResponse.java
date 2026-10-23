package dev.cauce.api.usage;

import dev.cauce.orchestration.usage.UsageReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The body of {@code GET /v1/tenants/{id}/usage} (ADR 0010, S3): what was asked, and one row per
 * period and tenant (or agent). Bounded by the range, so not paginated. Costs are in US$, the
 * provider's list price; {@code cost_usd} is null in a row where no call was priced.
 */
public record UsageResponse(UUID tenantId, LocalDate from, LocalDate to, String granularity, String groupBy,
                            String timeZone, String currency, List<Row> data) {

    /** One period of one tenant, or of one of its agents ({@code agent_id} null when grouped by tenant). */
    public record Row(String period, UUID tenantId, UUID agentId, long inputTokens, long cacheReadTokens,
                      long cacheWriteTokens, long outputTokens, long calls, long conversations, BigDecimal costUsd,
                      long unpricedCalls) {
    }

    public static UsageResponse from(UsageReport report) {
        return new UsageResponse(report.tenantId(), report.from(), report.to(),
                report.granularity().name().toLowerCase(Locale.ROOT), report.grouping().name().toLowerCase(Locale.ROOT),
                report.zone().getId(), "USD", report.rows().stream().map(r -> new Row(r.period(), r.tenantId(),
                        r.agentId(), r.inputTokens(), r.cacheReadTokens(), r.cacheWriteTokens(), r.outputTokens(),
                        r.calls(), r.conversations(), r.costUsd(), r.unpricedCalls())).toList());
    }
}
