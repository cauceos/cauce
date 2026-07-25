package dev.cauce.channels;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.governance.audit.AuditChainVerifier;
import dev.cauce.governance.audit.AuditOutboxDrainService;
import dev.cauce.channels.support.AbstractChannelsIntegrationTest;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.TenantService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end test of the Family-B channel-administration audit wiring: binding a channel to
 * an agent writes {@code admin.channel.configured} atomically in the binding's transaction,
 * into the owning tenant's chain — and NO secret material (the provider credential, the
 * webhook secret, or its stored hash) ever reaches the sink, asserted against the real
 * values.
 */
class ChannelConfigAuditIT extends AbstractChannelsIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ChannelConfigService channelConfigService;

    @Autowired
    private AuditOutboxDrainService drainService;

    @Autowired
    private AuditChainVerifier chainVerifier;

    private JdbcTemplate jdbc;

    private Tenant partner;
    private Tenant client;
    private Agent agent;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(adminDataSource);
        truncateAll();
        TenantContext.clear();
        Tenant operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        client = tenantService.createClient("Client", partner.id());
        TenantContext.setCurrentTenantId(client.id());
        agent = agentService.createAgent(client.id(), "TelegramBot",
                "You are helpful.", "anthropic", "claude-sonnet-4-7");
        TenantContext.clear();
        // Seeding emits its own Family-B events by design; this IT asserts the CHANNEL
        // event in isolation (the rest is covered by cauce-tenancy's AuditAdminIT).
        jdbc.execute("TRUNCATE TABLE audit_chain_heads, audit_log_entries, audit_outbox");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createChannelConfig_writesAdminEventWithoutCredentialOrSecretAndChains() {
        String botToken = "7000000001:AAG-real-looking-bot-token-material";

        // A PARTNER binds the channel for its client's agent: subject chain = client,
        // actor = partner.
        TenantContext.setCurrentTenantId(partner.id());
        ChannelConfigCreationResult result =
                channelConfigService.createChannelConfig(agent.id(), "telegram", botToken);
        TenantContext.clear();

        String storedSecretHash = jdbc.queryForObject(
                "SELECT webhook_secret_hash FROM channel_configs WHERE id = ?",
                String.class, result.config().id());

        // No secret material anywhere in the trail — asserted against the real values.
        Integer leaks = jdbc.queryForObject(
                "SELECT count(*) FROM audit_outbox WHERE payload::text LIKE ? "
                        + "OR payload::text LIKE ? OR payload::text LIKE ?",
                Integer.class, "%" + botToken + "%", "%" + result.webhookSecret() + "%",
                "%" + storedSecretHash + "%");
        assertThat(leaks).isZero();

        drainAs(client.id());
        assertThat(jdbc.queryForList("SELECT event_type FROM audit_log_entries "
                        + "WHERE tenant_id = ? ORDER BY sequence_number", String.class,
                client.id()))
                .containsExactly("admin.channel.configured");
        assertThat(payloadField(client.id(), "channel_config_id"))
                .isEqualTo(result.config().id().toString());
        assertThat(payloadField(client.id(), "agent_id")).isEqualTo(agent.id().toString());
        assertThat(payloadField(client.id(), "channel_type")).isEqualTo("telegram");
        assertThat(payloadField(client.id(), "actor_tenant_id"))
                .isEqualTo(partner.id().toString());

        TenantContext.setCurrentTenantId(client.id());
        try {
            assertThat(chainVerifier.verifyChain(client.id()).valid()).isTrue();
        } finally {
            TenantContext.clear();
        }
        // RLS: the owning client sees its admin trail; no context sees nothing.
        assertThat(countAs("audit_log_entries", client.id())).isEqualTo(1);
        assertThat(countAs("audit_log_entries", null)).isZero();
    }

    private void drainAs(UUID tenantId) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            drainService.drainBatch(tenantId, 100);
        } finally {
            TenantContext.clear();
        }
    }

    private String payloadField(UUID tenantId, String field) {
        return jdbc.queryForObject(
                "SELECT payload ->> ? FROM audit_log_entries WHERE tenant_id = ?",
                String.class, field, tenantId);
    }
}
