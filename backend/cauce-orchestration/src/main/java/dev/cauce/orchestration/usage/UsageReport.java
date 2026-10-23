package dev.cauce.orchestration.usage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The usage of a tenant and its descendants over a range of dates (ADR 0010, S3): one row per
 * period and tenant, or per period and agent. Days and months are cut in {@code zone}; the range is
 * inclusive at both ends.
 */
public record UsageReport(UUID tenantId, LocalDate from, LocalDate to, Granularity granularity, Grouping grouping,
                          ZoneId zone, List<Row> rows) {

    public UsageReport {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(granularity, "granularity");
        Objects.requireNonNull(grouping, "grouping");
        Objects.requireNonNull(zone, "zone");
        rows = List.copyOf(rows);
    }

    /** How long a period is. */
    public enum Granularity {
        DAY,
        MONTH
    }

    /** What a row is per, within its period. */
    public enum Grouping {
        TENANT,
        AGENT
    }

    /**
     * The usage of one tenant (or one of its agents) in one period. {@code period} is
     * {@code yyyy-MM-dd} for a day and {@code yyyy-MM} for a month. {@code inputTokens} includes the
     * cached parts, and {@code cacheWriteLongTokens} is the part of the writes made for a long retention. A conversation counts in a period when the model was called in it during that
     * period. {@code costUsd} is the cost of the priced calls, null when none was priced;
     * {@code unpricedCalls} are the calls with no price in force when they were made.
     *
     * @param agentId null when the report is grouped by tenant
     */
    public record Row(String period, UUID tenantId, UUID agentId, long inputTokens, long cacheReadTokens,
                      long cacheWriteTokens, long cacheWriteLongTokens, long outputTokens, long calls,
                      long conversations, BigDecimal costUsd, long unpricedCalls) {

        public Row {
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(tenantId, "tenantId");
        }

        /** This row and {@code other}, of the same period and tenant, as one row of the tenant. */
        Row plus(Row other) {
            BigDecimal cost = costUsd == null ? other.costUsd
                    : other.costUsd == null ? costUsd : costUsd.add(other.costUsd);
            return new Row(period, tenantId, null, inputTokens + other.inputTokens,
                    cacheReadTokens + other.cacheReadTokens, cacheWriteTokens + other.cacheWriteTokens,
                    cacheWriteLongTokens + other.cacheWriteLongTokens, outputTokens + other.outputTokens, calls + other.calls, conversations + other.conversations, cost,
                    unpricedCalls + other.unpricedCalls);
        }
    }
}
