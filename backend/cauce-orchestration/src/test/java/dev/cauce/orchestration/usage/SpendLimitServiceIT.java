package dev.cauce.orchestration.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.UuidGenerator;
import dev.cauce.core.agent.Agent;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.orchestration.support.AbstractOrchestrationIntegrationTest;
import dev.cauce.orchestration.usage.SpendGuard.Verdict;
import dev.cauce.orchestration.usage.SpendLimit.SpendState;
import dev.cauce.tenancy.TenantService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The spend limit against the real schema (V46, V48) as the runtime cauce_app role: only a strict
 * ancestor sets it — refused by the service and, underneath, by the policies — and the check of an
 * invocation warns at 80 %, refuses at 100 % and reports each threshold once a month.
 */
class SpendLimitServiceIT extends AbstractOrchestrationIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private SpendLimitService spendLimitService;

    @Autowired
    private SpendGuard spendGuard;

    private JdbcTemplate jdbc;
    private Tenant operator;
    private Tenant partner;
    private Tenant client;
    private Tenant otherPartner;
    private Agent agent;

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
        client = tenantService.createClient("Bar", partner.id());
        TenantContext.clear();
        agent = Agent.create(client.id(), "Camarero", "Eres el camarero.", "anthropic", "claude-sonnet-5-5");
        // 2.00 US$ per million input tokens; in force from long ago.
        jdbc.update("INSERT INTO llm_prices (id, tenant_id, provider, model, input_usd_per_mtok, output_usd_per_mtok, "
                + "valid_from) VALUES (?, ?, 'anthropic', 'claude-sonnet-5-5', 2.00, 10.00, now() - interval '1 day')",
                UuidGenerator.newV7(), operator.id());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void anAncestor_setsChangesAndRemovesTheLimit_andTheClientReadsIt() {
        TenantContext.setCurrentTenantId(partner.id());
        spendLimitService.setLimit(client.id(), new BigDecimal("50"));
        TenantContext.setCurrentTenantId(operator.id());
        spendLimitService.setLimit(client.id(), new BigDecimal("80.00"));

        TenantContext.setCurrentTenantId(client.id());
        SpendStatus status = spendLimitService.status(client.id());
        assertThat(status.monthlyLimitUsd()).isEqualByComparingTo("80.00");
        assertThat(status.state()).isEqualTo(SpendState.OK);
        assertThat(jdbc.queryForObject("SELECT set_by_tenant_id FROM tenant_spend_limits WHERE tenant_id = ?",
                UUID.class, client.id())).isEqualTo(operator.id());

        TenantContext.setCurrentTenantId(partner.id());
        spendLimitService.removeLimit(client.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_spend_limits", Integer.class)).isZero();
        List<String> changes = jdbc.queryForList("SELECT payload->>'change' FROM audit_outbox WHERE tenant_id = ? "
                + "AND event_type = 'admin.spend_limit.changed' ORDER BY created_at, id", String.class, client.id());
        assertThat(changes).containsExactly("set", "changed", "removed");
    }

    @Test
    void theClient_cannotSetItsOwnLimit_neitherThroughTheServiceNorUnderneath() throws SQLException {
        TenantContext.setCurrentTenantId(partner.id());
        spendLimitService.setLimit(client.id(), new BigDecimal("50"));

        TenantContext.setCurrentTenantId(client.id());
        assertThatThrownBy(() -> spendLimitService.setLimit(client.id(), new BigDecimal("5000")))
                .isInstanceOf(OwnSpendLimitException.class);
        // The V48 policies: the client sees its row but cannot change or remove it, nor insert one.
        assertThat(countAs("tenant_spend_limits", client.id())).isEqualTo(1);
        assertThat(updateAs(client.id(), "UPDATE tenant_spend_limits SET monthly_limit_usd = 5000")).isZero();
        assertThat(updateAs(client.id(), "DELETE FROM tenant_spend_limits")).isZero();
        assertThat(updateAs(partner.id(), "UPDATE tenant_spend_limits SET monthly_limit_usd = 60")).isEqualTo(1);
        jdbc.update("DELETE FROM tenant_spend_limits");
        assertThatThrownBy(() -> executeAs(client.id(), "INSERT INTO tenant_spend_limits (id, tenant_id, "
                + "monthly_limit_usd, set_by_tenant_id, updated_at) VALUES ('" + UuidGenerator.newV7() + "', '"
                + client.id() + "', 5000, '" + partner.id() + "', now())"))
                .isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
    }

    @Test
    void aSibling_neitherReadsNorSetsTheLimit() {
        TenantContext.setCurrentTenantId(otherPartner.id());

        assertThatThrownBy(() -> spendLimitService.status(client.id())).isInstanceOf(TenantNotFoundException.class);
        assertThatThrownBy(() -> spendLimitService.setLimit(client.id(), BigDecimal.TEN))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void theCheck_warnsAt80_refusesAt100_andReportsEachThresholdOnce() {
        TenantContext.setCurrentTenantId(partner.id());
        spendLimitService.setLimit(client.id(), new BigDecimal("1.00"));
        TenantContext.setCurrentTenantId(client.id()); // the worker's context: the invocation's tenant

        assertThat(spendGuard.check(agent)).isEqualTo(Verdict.ALLOWED);
        insertUsageNow(425_000); // 0.85 US$
        assertThat(spendGuard.check(agent)).isEqualTo(Verdict.ALLOWED);
        assertThat(spendGuard.check(agent)).isEqualTo(Verdict.ALLOWED);
        insertUsageNow(100_000); // 1.05 US$
        assertThat(spendGuard.check(agent)).isEqualTo(Verdict.LIMIT_REACHED);
        assertThat(spendGuard.check(agent)).isEqualTo(Verdict.LIMIT_REACHED);

        assertThat(jdbc.queryForList("SELECT threshold_percent FROM spend_alerts WHERE tenant_id = ? "
                + "ORDER BY threshold_percent", Integer.class, client.id())).containsExactly(80, 100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_outbox WHERE tenant_id = ? "
                + "AND event_type = 'conduct.spend.threshold_reached'", Integer.class, client.id())).isEqualTo(2);
        assertThat(spendLimitService.status(client.id()).state()).isEqualTo(SpendState.REACHED);
        assertThat(spendLimitService.status(client.id()).spentUsd()).isEqualByComparingTo("1.05");
    }

    @Test
    void theCheck_withALimit_refusesAModelWithoutPrice_andWithoutALimitLetsItRun() {
        Agent unpriced = Agent.create(client.id(), "Camarero", "Eres el camarero.", "anthropic", "claude-haiku-5-5");
        TenantContext.setCurrentTenantId(client.id());
        assertThat(spendGuard.check(unpriced)).isEqualTo(Verdict.ALLOWED);

        TenantContext.setCurrentTenantId(partner.id());
        spendLimitService.setLimit(client.id(), new BigDecimal("1.00"));
        TenantContext.setCurrentTenantId(client.id());
        assertThat(spendGuard.check(unpriced)).isEqualTo(Verdict.UNPRICED_MODEL);
        assertThat(spendGuard.check(agent)).isEqualTo(Verdict.ALLOWED);
    }

    @Test
    void theCheck_withALimit_refusesALongCacheAgent_untilItsPriceHasTheLongWrite() {
        Agent longCache = agent.withPromptCacheRetention(dev.cauce.core.agent.PromptCacheRetention.LONG);
        TenantContext.setCurrentTenantId(partner.id());
        spendLimitService.setLimit(client.id(), new BigDecimal("1.00"));
        TenantContext.setCurrentTenantId(client.id());
        assertThat(spendGuard.check(longCache)).isEqualTo(Verdict.UNPRICED_MODEL);
        assertThat(spendGuard.check(agent)).isEqualTo(Verdict.ALLOWED);

        // A new price row with the long write: the latest in force decides.
        jdbc.update("INSERT INTO llm_prices (id, tenant_id, provider, model, input_usd_per_mtok, output_usd_per_mtok, "
                + "cache_write_long_usd_per_mtok, valid_from) VALUES (?, ?, 'anthropic', 'claude-sonnet-5-5', 2.00, "
                + "10.00, 4.00, now() - interval '1 hour')", UuidGenerator.newV7(), operator.id());
        assertThat(spendGuard.check(longCache)).isEqualTo(Verdict.ALLOWED);
    }

    private void insertUsageNow(int inputTokens) {
        jdbc.update("INSERT INTO llm_usage_records (id, tenant_id, agent_id, conversation_id, invocation_id, provider, "
                        + "model, round_index, input_tokens, output_tokens, total_tokens, finish_reason) "
                        + "VALUES (?, ?, ?, ?, ?, 'anthropic', 'claude-sonnet-5-5', 0, ?, 0, ?, 'STOP')",
                UuidGenerator.newV7(), client.id(), agent.id(), UUID.randomUUID(), UUID.randomUUID(), inputTokens,
                inputTokens);
    }

    private void executeAs(UUID context, String sql) throws SQLException {
        try (Connection conn = appDataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL app.current_tenant_id = '" + context + "'");
                st.execute(sql);
            } finally {
                conn.rollback();
            }
        }
    }
}
