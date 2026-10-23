package dev.cauce.orchestration.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.UuidGenerator;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.orchestration.support.AbstractOrchestrationIntegrationTest;
import dev.cauce.orchestration.usage.UsageReport.Granularity;
import dev.cauce.orchestration.usage.UsageReport.Grouping;
import dev.cauce.orchestration.usage.UsageReport.Row;
import dev.cauce.tenancy.TenantService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The usage aggregation against the real schema (V19, V46) as the runtime cauce_app role: days and
 * months cut in the zone asked, conversations counted per period, unpriced calls apart, and the
 * hierarchy's visibility — up, never sideways.
 */
class LlmUsageQueryServiceIT extends AbstractOrchestrationIntegrationTest {

    private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate OCT_31 = LocalDate.of(2026, 10, 31);

    @Autowired
    private TenantService tenantService;

    @Autowired
    private LlmUsageQueryService queryService;

    private JdbcTemplate jdbc;
    private Tenant operator;
    private Tenant partner;
    private Tenant client;
    private Tenant otherClient;
    private Tenant otherPartner;
    private final UUID agent = UUID.randomUUID();
    private final UUID otherAgent = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(adminDataSource);
        truncateAll();
        TenantContext.clear();
        operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        otherPartner = tenantService.createPartner("Other partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        client = tenantService.createClient("Client", partner.id());
        otherClient = tenantService.createClient("Other client", partner.id());
        TenantContext.clear();
        // 2.00 in, 10.00 out per million; cached tokens as input.
        jdbc.update("INSERT INTO llm_prices (id, tenant_id, provider, model, input_usd_per_mtok, output_usd_per_mtok, "
                + "valid_from) VALUES (?, ?, 'anthropic', 'claude-sonnet-5-5', 2.00, 10.00, ?)", UuidGenerator.newV7(),
                operator.id(), Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void days_areCutInTheZoneAsked() {
        UUID conversation = UUID.randomUUID();
        insertUsage(client.id(), agent, conversation, "2026-10-10T12:00:00Z", 1_000_000, 0);
        // 23:30 UTC on the 10th is the 11th in Madrid.
        insertUsage(client.id(), agent, conversation, "2026-10-10T23:30:00Z", 1_000_000, 0);

        TenantContext.setCurrentTenantId(client.id());
        UsageReport utc = queryService.usage(client.id(), OCT_1, OCT_31, Granularity.DAY, Grouping.TENANT, "UTC");
        UsageReport madrid = queryService.usage(client.id(), OCT_1, OCT_31, Granularity.DAY, Grouping.TENANT,
                "Europe/Madrid");

        assertThat(utc.rows()).extracting(Row::period, Row::calls).containsExactly(
                org.assertj.core.groups.Tuple.tuple("2026-10-10", 2L));
        assertThat(madrid.rows()).extracting(Row::period, Row::calls).containsExactly(
                org.assertj.core.groups.Tuple.tuple("2026-10-10", 1L),
                org.assertj.core.groups.Tuple.tuple("2026-10-11", 1L));
    }

    @Test
    void aMonth_sumsTokensAndCost_countsEachConversationOnce_andKeepsUnpricedCallsApart() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertUsage(client.id(), agent, first, "2026-10-02T10:00:00Z", 1_000_000, 100_000);
        insertUsage(client.id(), agent, first, "2026-10-02T10:00:01Z", 500_000, 0);
        insertUsage(client.id(), otherAgent, second, "2026-10-20T10:00:00Z", 0, 0);
        jdbc.update("UPDATE llm_usage_records SET model = 'unpriced-model' WHERE agent_id = ?", otherAgent);
        insertUsage(client.id(), agent, first, "2026-11-01T00:00:00Z", 1_000_000, 0); // next month (UTC)

        TenantContext.setCurrentTenantId(client.id());
        UsageReport month = queryService.usage(client.id(), OCT_1, OCT_31, Granularity.MONTH, Grouping.TENANT, "UTC");
        UsageReport byAgent = queryService.usage(client.id(), OCT_1, OCT_31, Granularity.MONTH, Grouping.AGENT, "UTC");

        assertThat(month.rows()).hasSize(1);
        Row row = month.rows().get(0);
        assertThat(row.period()).isEqualTo("2026-10");
        assertThat(row.agentId()).isNull();
        assertThat(row.inputTokens()).isEqualTo(1_500_000);
        assertThat(row.outputTokens()).isEqualTo(100_000);
        assertThat(row.calls()).isEqualTo(3);
        assertThat(row.conversations()).isEqualTo(2);
        // 1.5M in × 2.00 + 100k out × 10.00, per million; the unpriced call adds nothing.
        assertThat(row.costUsd()).isEqualByComparingTo("4.00");
        assertThat(row.unpricedCalls()).isEqualTo(1);
        assertThat(byAgent.rows()).extracting(Row::agentId).containsExactlyInAnyOrder(agent, otherAgent);
        assertThat(byAgent.rows()).filteredOn(r -> r.agentId().equals(otherAgent)).singleElement()
                .satisfies(r -> assertThat(r.costUsd()).isNull());
    }

    @Test
    void aPartner_seesItsClients_aClientOnlyItself_andNobodySideways() {
        insertUsage(client.id(), agent, UUID.randomUUID(), "2026-10-02T10:00:00Z", 1_000, 0);
        insertUsage(otherClient.id(), otherAgent, UUID.randomUUID(), "2026-10-02T10:00:00Z", 1_000, 0);

        TenantContext.setCurrentTenantId(partner.id());
        assertThat(queryService.usage(partner.id(), OCT_1, OCT_31, Granularity.MONTH, Grouping.TENANT, "UTC").rows())
                .extracting(Row::tenantId).containsExactlyInAnyOrder(client.id(), otherClient.id());
        assertThat(queryService.usage(client.id(), OCT_1, OCT_31, Granularity.MONTH, Grouping.TENANT, "UTC").rows())
                .extracting(Row::tenantId).containsExactly(client.id());
        TenantContext.setCurrentTenantId(operator.id());
        assertThat(queryService.usage(operator.id(), OCT_1, OCT_31, Granularity.MONTH, Grouping.TENANT, "UTC")
                .rows()).hasSize(2);

        TenantContext.setCurrentTenantId(client.id());
        assertThat(queryService.usage(client.id(), OCT_1, OCT_31, Granularity.MONTH, Grouping.TENANT, "UTC").rows())
                .extracting(Row::tenantId).containsExactly(client.id());
        assertThatThrownBy(() -> queryService.usage(otherClient.id(), OCT_1, OCT_31, Granularity.MONTH,
                Grouping.TENANT, "UTC")).isInstanceOf(TenantNotFoundException.class);
        assertThatThrownBy(() -> queryService.usage(partner.id(), OCT_1, OCT_31, Granularity.MONTH,
                Grouping.TENANT, "UTC")).isInstanceOf(TenantNotFoundException.class);
        TenantContext.setCurrentTenantId(otherPartner.id());
        assertThatThrownBy(() -> queryService.usage(client.id(), OCT_1, OCT_31, Granularity.MONTH,
                Grouping.TENANT, "UTC")).isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void aReadOfAnotherTenant_isAuditedInItsChain_andAReadOfOneselfIsNot() {
        TenantContext.setCurrentTenantId(client.id());
        queryService.usage(client.id(), OCT_1, OCT_31, Granularity.DAY, Grouping.TENANT, "UTC");
        TenantContext.setCurrentTenantId(partner.id());
        queryService.usage(client.id(), OCT_1, OCT_31, Granularity.DAY, Grouping.AGENT, "UTC");

        List<String> actors = jdbc.queryForList("SELECT payload->>'actor_tenant_id' FROM audit_outbox "
                + "WHERE tenant_id = ? AND event_type = 'admin.usage.read'", String.class, client.id());
        assertThat(actors).containsExactly(partner.id().toString());
    }

    private void insertUsage(UUID tenantId, UUID agentId, UUID conversationId, String createdAt, int input,
                             int output) {
        jdbc.update("INSERT INTO llm_usage_records (id, tenant_id, agent_id, conversation_id, invocation_id, provider, "
                        + "model, round_index, input_tokens, output_tokens, total_tokens, finish_reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'anthropic', 'claude-sonnet-5-5', 0, ?, ?, ?, 'STOP', ?)",
                UuidGenerator.newV7(), tenantId, agentId, conversationId, UUID.randomUUID(), input, output,
                input + output, Timestamp.from(Instant.parse(createdAt)));
    }
}
