package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.agent.AgentStatus;
import dev.cauce.core.tenant.MissingTenantContextException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests for AgentService and the agents RLS policy. The Spring datasource
 * connects as the container superuser (mirroring the current app), so RLS filtering
 * is verified through a dedicated restricted role on data created by the service.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AgentServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant clientA;
    private Tenant clientB;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE api_keys, pending_invocations, messages, conversations, agents, tenants CASCADE");
        // cauce_app and its grants come from Flyway migration V10; tests SET ROLE to it.
        TenantContext.clear();

        operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        clientA = tenantService.createClient("Client A", partner.id());
        clientB = tenantService.createClient("Client B", partner.id());
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createAgent_forClient_persistsDraftAgent() {
        Agent agent = createAgentForClientA();

        assertThat(agent.status()).isEqualTo(AgentStatus.DRAFT);
        assertThat(agent.tenantId()).isEqualTo(clientA.id());
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE id = ?", Integer.class, agent.id());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void createAgent_withoutLlmConfig_persistsDefaultsInDatabase() {
        Agent agent = createAgentForClientA();

        Double temperature = jdbc.queryForObject(
                "SELECT temperature FROM agents WHERE id = ?", Double.class, agent.id());
        Integer maxTokens = jdbc.queryForObject(
                "SELECT max_response_tokens FROM agents WHERE id = ?", Integer.class, agent.id());
        assertThat(temperature).isEqualTo(0.7);
        assertThat(maxTokens).isEqualTo(4096);
    }

    @Test
    void createAgent_withCustomLlmConfig_persistsThoseValues() {
        TenantContext.setCurrentTenantId(clientA.id());
        Agent agent;
        try {
            agent = agentService.createAgent(clientA.id(), "DentalBot",
                    "You are a dentist receptionist.", "anthropic", "claude-sonnet-4-7", 0.25, 16000);
        } finally {
            TenantContext.clear();
        }

        assertThat(agent.temperature()).isEqualTo(0.25);
        assertThat(agent.maxResponseTokens()).isEqualTo(16000);
        Double temperature = jdbc.queryForObject(
                "SELECT temperature FROM agents WHERE id = ?", Double.class, agent.id());
        Integer maxTokens = jdbc.queryForObject(
                "SELECT max_response_tokens FROM agents WHERE id = ?", Integer.class, agent.id());
        assertThat(temperature).isEqualTo(0.25);
        assertThat(maxTokens).isEqualTo(16000);
    }

    @Test
    void temperatureCheckConstraint_rejectsOutOfRangeValueOnDirectInsert() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO agents (id, tenant_id, name, system_prompt, model_provider, model_name, "
                        + "temperature, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'Bot', 'p', 'anthropic', 'claude-sonnet-4-7', 2.0, 'DRAFT', "
                        + "now(), now())",
                UUID.randomUUID(), clientA.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void createAgent_withoutContext_throwsMissingTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() ->
                agentService.createAgent(clientA.id(), "Bot", "p", "anthropic", "m"))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void getAgent_returnsAgentById_emptyForUnknownId() {
        // getAgent now relies entirely on RLS for visibility (no app-level owner filter). This IT
        // connects as the RLS-bypassing superuser, so it can only assert the lookup round-trip;
        // hierarchical visibility (owner/partner/operator see it, sibling does not) is verified by
        // agentsCreatedViaService_areFilteredByRlsHierarchy via SET ROLE, and end-to-end under
        // cauce_app by TenantAndAgentApiIT.
        Agent agent = createAgentForClientA();
        TenantContext.setCurrentTenantId(clientA.id());

        assertThat(agentService.getAgent(agent.id())).isPresent();
        assertThat(agentService.getAgent(UUID.randomUUID())).isEmpty();
    }

    @Test
    void agentsCreatedViaService_areFilteredByRlsHierarchy() throws SQLException {
        createAgentForClientA();

        assertThat(countAgentsVisibleAs(clientA.id())).isEqualTo(1); // owner sees it
        assertThat(countAgentsVisibleAs(partner.id())).isEqualTo(1); // its partner sees it
        assertThat(countAgentsVisibleAs(operator.id())).isEqualTo(1); // its operator sees it
        assertThat(countAgentsVisibleAs(clientB.id())).isZero();      // sibling client does not
        assertThat(countAgentsVisibleAs(null)).isZero();             // no context => nothing
    }

    @Test
    void uuidV7Ordering_whenCreatingMultipleAgents_thenIdsAreTimeOrdered() throws InterruptedException {
        TenantContext.setCurrentTenantId(clientA.id());
        List<UUID> creationOrder = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            creationOrder.add(agentService.createAgent(
                    clientA.id(), "Bot " + i, "p", "anthropic", "claude-sonnet-4-7").id());
            Thread.sleep(2);
        }
        TenantContext.clear();

        List<UUID> byId = jdbc.queryForList("SELECT id FROM agents ORDER BY id", UUID.class);

        assertThat(byId).containsExactlyElementsOf(creationOrder);
    }

    private Agent createAgentForClientA() {
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            return agentService.createAgent(clientA.id(), "DentalBot",
                    "You are a dentist receptionist.", "anthropic", "claude-sonnet-4-7");
        } finally {
            TenantContext.clear();
        }
    }

    private long countAgentsVisibleAs(UUID context) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE cauce_app");
                if (context != null) {
                    st.execute("SET LOCAL app.current_tenant_id = '" + context + "'");
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM agents")) {
                    rs.next();
                    return rs.getLong(1);
                }
            } finally {
                conn.rollback();
            }
        }
    }
}
