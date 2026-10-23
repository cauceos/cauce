package dev.cauce.orchestration.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.UuidGenerator;
import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.support.AbstractOrchestrationIntegrationTest;
import dev.cauce.tenancy.TenantService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The price table and the cost view against the real schema (V46), as the runtime cauce_app role:
 * only the operator adds prices, every tenant of the hierarchy reads them, nobody changes or removes
 * one, and each usage row is priced with the price in force at its {@code created_at} — or not at
 * all.
 */
class LlmPriceServiceIT extends AbstractOrchestrationIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private LlmPriceService priceService;

    private JdbcTemplate jdbc;
    private Tenant operator;
    private Tenant partner;
    private Tenant client;
    private Tenant otherPartner;

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
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void addPrice_byTheOperator_isStoredInForceFromTheDatabaseClock_andAudited() {
        TenantContext.setCurrentTenantId(operator.id());
        Instant before = clock.instant();

        LlmPrice price = priceService.addPrice("anthropic", "claude-sonnet-5-5", new BigDecimal("2.00"),
                new BigDecimal("10.00"), new BigDecimal("0.20"), null, null);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM llm_prices WHERE id = ?", price.id());
        assertThat(row.get("tenant_id")).isEqualTo(operator.id());
        assertThat((BigDecimal) row.get("input_usd_per_mtok")).isEqualByComparingTo("2.00");
        assertThat(row.get("cache_write_usd_per_mtok")).isNull();
        assertThat(((Timestamp) row.get("valid_from")).toInstant()).isAfterOrEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_outbox WHERE tenant_id = ? "
                + "AND event_type = 'admin.llm_price.added' AND payload->>'price_id' = ?", Integer.class,
                operator.id(), price.id().toString())).isEqualTo(1);
    }

    @Test
    void addPrice_byAPartnerOrAClient_isRefused() {
        TenantContext.setCurrentTenantId(partner.id());
        assertThatThrownBy(() -> priceService.addPrice("anthropic", "m", BigDecimal.ONE, BigDecimal.ONE, null, null, null))
                .isInstanceOf(InvalidTenantTierException.class);
        TenantContext.setCurrentTenantId(client.id());
        assertThatThrownBy(() -> priceService.addPrice("anthropic", "m", BigDecimal.ONE, BigDecimal.ONE, null, null, null))
                .isInstanceOf(InvalidTenantTierException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_prices", Integer.class)).isZero();
    }

    @Test
    void prices_areReadByTheWholeHierarchy_andNeverChangedOrRemoved() {
        TenantContext.setCurrentTenantId(operator.id());
        LlmPrice price = priceService.addPrice("anthropic", "m", BigDecimal.ONE, BigDecimal.TEN, null, null, null);

        assertThat(countAs("llm_prices", operator.id())).isEqualTo(1);
        assertThat(countAs("llm_prices", partner.id())).isEqualTo(1);
        assertThat(countAs("llm_prices", client.id())).isEqualTo(1);
        assertThat(countAs("llm_prices", null)).isZero();
        TenantContext.setCurrentTenantId(client.id());
        assertThat(priceService.listPrices(null, 10)).extracting(LlmPrice::id).containsExactly(price.id());

        // No UPDATE or DELETE policy: not even the owner changes or removes a price.
        assertThat(updateAs(operator.id(), "UPDATE llm_prices SET input_usd_per_mtok = 0")).isZero();
        assertThat(updateAs(operator.id(), "DELETE FROM llm_prices")).isZero();
    }

    @Test
    void costView_pricesEachCallWithThePriceInForceAtItsCreation() {
        // The cache prices of the first version are absent: cached tokens are priced as input.
        insertPrice("2026-10-05T00:00:00Z", "2.00", "10.00", null, null);
        insertPrice("2026-10-20T00:00:00Z", "3.00", "15.00", "0.30", "3.75");
        UUID beforeAnyPrice = insertUsage(client.id(), "2026-10-04T12:00:00Z", 1_000_000, 0, 0, 0);
        UUID firstVersion = insertUsage(client.id(), "2026-10-10T12:00:00Z", 1_000_000, 400_000, 100_000, 10_000);
        UUID secondVersion = insertUsage(client.id(), "2026-10-21T12:00:00Z", 1_000_000, 400_000, 100_000, 10_000);
        UUID otherModel = insertUsage(client.id(), "2026-10-21T12:00:00Z", "claude-haiku-5-5", 1_000, 0, 0, 0);

        Map<UUID, BigDecimal> costs = costsAs(client.id());

        assertThat(costs).containsKeys(beforeAnyPrice, firstVersion, secondVersion, otherModel);
        assertThat(costs.get(beforeAnyPrice)).isNull();
        assertThat(costs.get(otherModel)).isNull();
        // 1M input at 2.00 (all of it, cached parts as input) + 10k output at 10.00.
        assertThat(costs.get(firstVersion)).isEqualByComparingTo("2.10");
        // 500k uncached × 3.00 + 400k read × 0.30 + 100k written × 3.75 + 10k out × 15.00, per million.
        assertThat(costs.get(secondVersion)).isEqualByComparingTo("2.145");
    }

    @Test
    void costView_pricesLongRetentionWritesAtTheirOwnPrice() {
        jdbc.update("INSERT INTO llm_prices (id, tenant_id, provider, model, input_usd_per_mtok, output_usd_per_mtok, "
                        + "cache_read_usd_per_mtok, cache_write_usd_per_mtok, cache_write_long_usd_per_mtok, valid_from) "
                        + "VALUES (?, ?, 'anthropic', 'claude-sonnet-5-5', 2.00, 10.00, 0.10, 2.50, 4.00, ?)",
                UuidGenerator.newV7(), operator.id(), Timestamp.from(Instant.parse("2026-10-05T00:00:00Z")));
        UUID call = insertUsage(client.id(), "2026-10-10T12:00:00Z", 1_000_000, 0, 600_000, 0);
        jdbc.update("UPDATE llm_usage_records SET cache_write_long_tokens = 400000 WHERE id = ?", call);

        // 400k uncached × 2.00 + 200k short writes × 2.50 + 400k long writes × 4.00, per million.
        assertThat(costsAs(client.id()).get(call)).isEqualByComparingTo("2.90");
        // The long writes are part of the writes (V49's CHECK).
        assertThatThrownBy(() -> jdbc.update("UPDATE llm_usage_records SET cache_write_long_tokens = 700000 WHERE id = ?",
                call)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void costView_aLongWriteWithoutItsPrice_isUnpriced_butACallWithoutLongWritesIsPriced() {
        // A price row with the short cache prices only.
        insertPrice("2026-10-05T00:00:00Z", "2.00", "10.00", "0.10", "2.50");
        UUID longWrites = insertUsage(client.id(), "2026-10-10T12:00:00Z", 1_000_000, 0, 600_000, 0);
        jdbc.update("UPDATE llm_usage_records SET cache_write_long_tokens = 400000 WHERE id = ?", longWrites);
        UUID shortWrites = insertUsage(client.id(), "2026-10-10T12:00:00Z", 1_000_000, 0, 600_000, 0);

        Map<UUID, BigDecimal> costs = costsAs(client.id());

        assertThat(costs).containsKey(longWrites);
        assertThat(costs.get(longWrites)).isNull();
        // 400k uncached × 2.00 + 600k short writes × 2.50, per million.
        assertThat(costs.get(shortWrites)).isEqualByComparingTo("2.30");
    }

    @Test
    void costView_withTwoPricesFromTheSameInstant_usesTheOneAddedLast() {
        insertPrice("2026-10-05T00:00:00Z", "2.00", "10.00", null, null);
        insertPrice("2026-10-05T00:00:00Z", "4.00", "10.00", null, null);
        UUID call = insertUsage(client.id(), "2026-10-10T12:00:00Z", 1_000_000, 0, 0, 0);

        assertThat(costsAs(client.id()).get(call)).isEqualByComparingTo("4.00");
    }

    @Test
    void costView_followsTheLedgersVisibility() {
        insertPrice("2026-10-05T00:00:00Z", "2.00", "10.00", null, null);
        insertUsage(client.id(), "2026-10-10T12:00:00Z", 1_000, 0, 0, 0);

        assertThat(costsAs(client.id())).hasSize(1);
        assertThat(costsAs(partner.id())).hasSize(1);
        assertThat(costsAs(operator.id())).hasSize(1);
        assertThat(costsAs(otherPartner.id())).isEmpty();
        assertThat(costsAs(null)).isEmpty();
    }

    private void insertPrice(String validFrom, String input, String output, String cacheRead, String cacheWrite) {
        jdbc.update("INSERT INTO llm_prices (id, tenant_id, provider, model, input_usd_per_mtok, output_usd_per_mtok, "
                        + "cache_read_usd_per_mtok, cache_write_usd_per_mtok, valid_from) "
                        + "VALUES (?, ?, 'anthropic', 'claude-sonnet-5-5', ?, ?, ?, ?, ?)",
                UuidGenerator.newV7(), operator.id(), new BigDecimal(input), new BigDecimal(output),
                cacheRead == null ? null : new BigDecimal(cacheRead),
                cacheWrite == null ? null : new BigDecimal(cacheWrite), Timestamp.from(Instant.parse(validFrom)));
    }

    private UUID insertUsage(UUID tenantId, String createdAt, int input, int cacheRead, int cacheWrite, int output) {
        return insertUsage(tenantId, createdAt, "claude-sonnet-5-5", input, cacheRead, cacheWrite, output);
    }

    private UUID insertUsage(UUID tenantId, String createdAt, String model, int input, int cacheRead, int cacheWrite,
                             int output) {
        UUID id = UuidGenerator.newV7();
        jdbc.update("INSERT INTO llm_usage_records (id, tenant_id, agent_id, conversation_id, invocation_id, provider, "
                        + "model, round_index, input_tokens, output_tokens, total_tokens, cache_read_tokens, "
                        + "cache_write_tokens, finish_reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'anthropic', ?, 0, ?, ?, ?, ?, ?, 'STOP', ?)",
                id, tenantId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), model, input, output,
                input + output, cacheRead, cacheWrite, Timestamp.from(Instant.parse(createdAt)));
        return id;
    }

    /** Every row of the cost view {@code context} sees, as cauce_app, with its cost. */
    private Map<UUID, BigDecimal> costsAs(UUID context) {
        try (Connection conn = appDataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                if (context != null) {
                    st.execute("SET LOCAL app.current_tenant_id = '" + context + "'");
                }
                Map<UUID, BigDecimal> costs = new HashMap<>();
                try (PreparedStatement ps = conn.prepareStatement("SELECT id, cost_usd FROM llm_usage_costs");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        costs.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
                    }
                }
                return costs;
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
