package dev.cauce.channels;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.config.ChannelConfigStatus;
import dev.cauce.channels.support.AbstractChannelsIntegrationTest;
import dev.cauce.core.agent.Agent;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.TenantService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for {@link ChannelConfigService} against a real PostgreSQL via
 * Testcontainers, exercising the runtime {@code cauce_app}/RLS path: hierarchical
 * visibility of the binding rows and the V16 SECURITY DEFINER resolution the
 * unauthenticated webhook path depends on (ADR 0001).
 */
class ChannelConfigServiceIT extends AbstractChannelsIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ChannelConfigService channelConfigService;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant clientA;
    private Tenant clientB;
    private Agent agent; // owned by clientA

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(adminDataSource);
        truncateAll();
        TenantContext.clear();

        operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        clientA = tenantService.createClient("Client A", partner.id());
        clientB = tenantService.createClient("Client B", partner.id());
        TenantContext.setCurrentTenantId(clientA.id());
        agent = agentService.createAgent(clientA.id(), "DentalBot",
                "You are a dentist receptionist.", "anthropic", "claude-sonnet-4-7");
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createChannelConfig_boundByThePartner_isOwnedByTheAgentClientTenant() {
        // A partner binds a channel on behalf of its client: hierarchical RLS lets it see
        // the agent, and the row must belong to the CLIENT for visibility to hold.
        TenantContext.setCurrentTenantId(partner.id());
        ChannelConfigCreationResult result;
        try {
            result = channelConfigService.createChannelConfig(agent.id(), "telegram", "12345:token");
        } finally {
            TenantContext.clear();
        }

        assertThat(result.config().tenantId()).isEqualTo(clientA.id());
        assertThat(result.webhookSecret()).isNotBlank();
        String storedHash = jdbc.queryForObject(
                "SELECT webhook_secret_hash FROM channel_configs WHERE id = ?",
                String.class, result.config().id());
        assertThat(storedHash).isNotEqualTo(result.webhookSecret());
    }

    @Test
    void channelConfigs_respectHierarchicalVisibility() {
        createAsClientA();

        assertThat(countAs("channel_configs", operator.id())).isEqualTo(1);
        assertThat(countAs("channel_configs", partner.id())).isEqualTo(1);
        assertThat(countAs("channel_configs", clientA.id())).isEqualTo(1);
        assertThat(countAs("channel_configs", clientB.id())).isZero();
        assertThat(countAs("channel_configs", null)).isZero();
    }

    @Test
    void resolveActiveChannelConfig_withoutTenantContext_resolvesViaTheEscapeHatch() {
        ChannelConfig created = createAsClientA().config();

        // The webhook path: no TenantContext at all — RLS alone would fail-close this read.
        Optional<ChannelConfig> resolved =
                channelConfigService.resolveActiveChannelConfig(created.id());

        assertThat(resolved).hasValueSatisfying(config -> {
            assertThat(config.tenantId()).isEqualTo(clientA.id());
            assertThat(config.agentId()).isEqualTo(agent.id());
            assertThat(config.channelType()).isEqualTo("telegram");
        });
    }

    @Test
    void resolveActiveChannelConfig_disabledConfig_isNotResolved() {
        ChannelConfig created = createAsClientA().config();
        jdbc.update("UPDATE channel_configs SET status = 'DISABLED' WHERE id = ?", created.id());

        assertThat(channelConfigService.resolveActiveChannelConfig(created.id())).isEmpty();
        assertThat(created.status()).isEqualTo(ChannelConfigStatus.ACTIVE); // domain untouched
    }

    @Test
    void resolveActiveChannelConfig_unknownId_isEmpty() {
        assertThat(channelConfigService.resolveActiveChannelConfig(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findActiveConfigs_underTenantContext_returnsOnlyActiveBindings() {
        ChannelConfig created = createAsClientA().config();
        ChannelConfig disabled = createAsClientA().config();
        jdbc.update("UPDATE channel_configs SET status = 'DISABLED' WHERE id = ?", disabled.id());

        TenantContext.setCurrentTenantId(clientA.id());
        try {
            assertThat(channelConfigService.findActiveConfigs(agent.id(), "telegram"))
                    .extracting(ChannelConfig::id)
                    .containsExactly(created.id());
        } finally {
            TenantContext.clear();
        }
    }

    private ChannelConfigCreationResult createAsClientA() {
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            return channelConfigService.createChannelConfig(agent.id(), "telegram", "12345:token");
        } finally {
            TenantContext.clear();
        }
    }
}
