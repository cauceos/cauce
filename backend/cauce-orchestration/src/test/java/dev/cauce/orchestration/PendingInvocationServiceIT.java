package dev.cauce.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageNotFoundException;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tenant.MissingTenantContextException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.MessageService;
import dev.cauce.tenancy.TenantService;
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
 * End-to-end tests for PendingInvocationService and the pending_invocations RLS policy. The
 * Spring datasource connects as the container superuser (mirroring the current app), so RLS
 * filtering is verified through a dedicated restricted role on data created by the service.
 * A pending invocation is visible exactly when its owning tenant is visible.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PendingInvocationServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private PendingInvocationService pendingInvocationService;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant clientA;
    private Tenant clientB;
    private Agent agent;                // owned by clientA
    private Conversation conversation;  // of agent
    private Message triggerMessage;     // USER message in conversation

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE api_keys, pending_invocations, messages, conversations, agents, tenants CASCADE");
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cauce_app') "
                + "THEN CREATE ROLE cauce_app; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO cauce_app");
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON "
                + "tenants, agents, conversations, messages, pending_invocations TO cauce_app");
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
        conversation = conversationService.startConversation(agent.id(), "whatsapp", "+34612345678");
        triggerMessage = messageService.appendMessage(conversation.id(), MessageRole.USER, "I need help");
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void enqueueInvocation_persistsPendingInvocationOwnedByClient() {
        PendingInvocation invocation = enqueueAs(clientA.id());

        assertThat(invocation.status()).isEqualTo(PendingInvocationStatus.PENDING);
        assertThat(invocation.conversationId()).isEqualTo(conversation.id());
        assertThat(invocation.triggerMessageId()).isEqualTo(triggerMessage.id());

        UUID storedTenant = jdbc.queryForObject(
                "SELECT tenant_id FROM pending_invocations WHERE id = ?", UUID.class, invocation.id());
        assertThat(storedTenant).isEqualTo(clientA.id());
    }

    @Test
    void enqueueInvocation_byPartnerForItsClient_ownsClientTenantNotActingPartner() {
        // A partner acts on behalf of its client; the row must belong to the client (clientA),
        // not the acting partner, so hierarchical RLS keeps it visible to the client itself.
        PendingInvocation invocation = enqueueAs(partner.id());

        assertThat(invocation.tenantId()).isEqualTo(clientA.id());
    }

    @Test
    void enqueueInvocation_whenMessageNotInConversation_throwsMessageNotFound() {
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            assertThatThrownBy(() ->
                    pendingInvocationService.enqueueInvocation(conversation.id(), UUID.randomUUID()))
                    .isInstanceOf(MessageNotFoundException.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void pendingInvocationsCreatedViaService_areFilteredByRlsHierarchy() throws SQLException {
        enqueueAs(clientA.id());

        assertThat(countVisibleAs(clientA.id())).isEqualTo(1);  // owner sees it
        assertThat(countVisibleAs(partner.id())).isEqualTo(1);  // its partner sees it
        assertThat(countVisibleAs(operator.id())).isEqualTo(1); // its operator sees it
        assertThat(countVisibleAs(clientB.id())).isZero();      // sibling client does not
        assertThat(countVisibleAs(null)).isZero();              // no context => nothing
    }

    @Test
    void enqueueInvocation_withoutContext_throwsMissingTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() ->
                pendingInvocationService.enqueueInvocation(conversation.id(), triggerMessage.id()))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void uuidV7Ordering_whenEnqueuingMultiple_thenIdsAreTimeOrdered() throws InterruptedException {
        List<UUID> creationOrder = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            creationOrder.add(enqueueAs(clientA.id()).id());
            Thread.sleep(2);
        }

        List<UUID> byId = jdbc.queryForList("SELECT id FROM pending_invocations ORDER BY id", UUID.class);

        assertThat(byId).containsExactlyElementsOf(creationOrder);
    }

    @Test
    void coherenceCheck_processingRowWithoutClaim_isRejectedByDatabase() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO pending_invocations "
                        + "(id, tenant_id, conversation_id, trigger_message_id, status, "
                        + "attempt_count, max_attempts, created_at) "
                        + "VALUES (?, ?, ?, ?, 'PROCESSING', 1, 3, now())",
                UUID.randomUUID(), clientA.id(), conversation.id(), triggerMessage.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private PendingInvocation enqueueAs(UUID context) {
        TenantContext.setCurrentTenantId(context);
        try {
            return pendingInvocationService.enqueueInvocation(conversation.id(), triggerMessage.id());
        } finally {
            TenantContext.clear();
        }
    }

    private long countVisibleAs(UUID context) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE cauce_app");
                if (context != null) {
                    st.execute("SET LOCAL app.current_tenant_id = '" + context + "'");
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pending_invocations")) {
                    rs.next();
                    return rs.getLong(1);
                }
            } finally {
                conn.rollback();
            }
        }
    }
}
