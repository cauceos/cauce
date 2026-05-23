package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.UuidGenerator;
import dev.cauce.core.agent.Agent;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.conversation.InvalidConversationTransitionException;
import dev.cauce.core.tenant.MissingTenantContextException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests for ConversationService and the conversations RLS policy. The Spring
 * datasource connects as the container superuser (mirroring the current app), so RLS
 * filtering is verified through a dedicated restricted role on data created by the
 * service. Visibility is inherited transitively: a conversation is visible iff its
 * agent is visible iff the agent's tenant is visible.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ConversationServiceIT {

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
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant clientA;
    private Tenant clientB;
    private Agent agent; // owned by clientA

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE messages, conversations, agents, tenants CASCADE");
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cauce_app') "
                + "THEN CREATE ROLE cauce_app; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO cauce_app");
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON tenants, agents, conversations TO cauce_app");
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
    void startConversation_forVisibleAgent_persistsOpenConversation() {
        Conversation conversation = startForClientA("whatsapp", "+34612345678");

        assertThat(conversation.status()).isEqualTo(ConversationStatus.OPEN);
        assertThat(conversation.agentId()).isEqualTo(agent.id());
        assertThat(conversation.closedAt()).isNull();
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE id = ?", Integer.class, conversation.id());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void startConversation_withoutContext_throwsMissingTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() ->
                conversationService.startConversation(agent.id(), "whatsapp", "+34612345678"))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void startConversation_underPartnerContext_succeeds() {
        // B2B2B: the partner operates on behalf of its client (e.g. an inbound webhook),
        // so RLS makes the client's agent visible and the conversation is created.
        TenantContext.setCurrentTenantId(partner.id());
        try {
            Conversation conversation =
                    conversationService.startConversation(agent.id(), "whatsapp", "+34612345678");
            assertThat(conversation.agentId()).isEqualTo(agent.id());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void conversationsCreatedViaService_areFilteredByRlsHierarchy() throws SQLException {
        startForClientA("whatsapp", "+34612345678");

        assertThat(countConversationsVisibleAs(clientA.id())).isEqualTo(1);  // owner sees it
        assertThat(countConversationsVisibleAs(partner.id())).isEqualTo(1);  // its partner sees it
        assertThat(countConversationsVisibleAs(operator.id())).isEqualTo(1); // its operator sees it
        assertThat(countConversationsVisibleAs(clientB.id())).isZero();      // sibling client does not
        assertThat(countConversationsVisibleAs(null)).isZero();              // no context => nothing
    }

    @Test
    void getConversation_returnsConversationVisibleToContext() {
        Conversation created = startForClientA("web_chat", "session-xyz");

        TenantContext.setCurrentTenantId(clientA.id());
        try {
            Optional<Conversation> found = conversationService.getConversation(created.id());
            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo(created.id());
            assertThat(found.get().channelType()).isEqualTo("web_chat");
            assertThat(found.get().externalIdentityRef()).isEqualTo("session-xyz");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void findActiveConversation_returnsOnlyTheOpenConversationForTheTuple() {
        Conversation open = startForClientA("whatsapp", "+34600111222");
        // A CLOSED thread for a different user on the same channel; not yet reachable via
        // domain transitions, so it is seeded directly.
        seedConversation("whatsapp", "+34699888777", ConversationStatus.CLOSED);

        TenantContext.setCurrentTenantId(clientA.id());
        try {
            assertThat(conversationService.findActiveConversation(agent.id(), "whatsapp", "+34600111222"))
                    .get()
                    .satisfies(c -> {
                        assertThat(c.id()).isEqualTo(open.id());
                        assertThat(c.status()).isEqualTo(ConversationStatus.OPEN);
                    });
            // Only a CLOSED conversation exists for this user => no active one.
            assertThat(conversationService.findActiveConversation(agent.id(), "whatsapp", "+34699888777"))
                    .isEmpty();
            // No conversation at all on this channel for this user.
            assertThat(conversationService.findActiveConversation(agent.id(), "voice", "+34600111222"))
                    .isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void uuidV7Ordering_whenCreatingMultipleConversations_thenIdsAreTimeOrdered()
            throws InterruptedException {
        TenantContext.setCurrentTenantId(clientA.id());
        List<UUID> creationOrder = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            creationOrder.add(conversationService
                    .startConversation(agent.id(), "whatsapp", "+3460000000" + i).id());
            Thread.sleep(2);
        }
        TenantContext.clear();

        List<UUID> byId = jdbc.queryForList("SELECT id FROM conversations ORDER BY id", UUID.class);

        assertThat(byId).containsExactlyElementsOf(creationOrder);
    }

    @Test
    void lifecycle_startEscalateCloseArchive_persistsEachStateInDb() {
        Conversation conv = startForClientA("whatsapp", "+34612345678");

        asClientA(() -> conversationService.escalateConversation(conv.id()));
        assertThat(readStatus(conv.id())).isEqualTo("ESCALATED");
        assertThat(readTimestamp(conv.id(), "escalated_at")).isNotNull();

        asClientA(() -> conversationService.closeConversation(conv.id()));
        assertThat(readStatus(conv.id())).isEqualTo("CLOSED");
        assertThat(readTimestamp(conv.id(), "closed_at")).isNotNull();
        assertThat(readTimestamp(conv.id(), "escalated_at")).isNotNull(); // preserved

        asClientA(() -> conversationService.archiveConversation(conv.id()));
        assertThat(readStatus(conv.id())).isEqualTo("ARCHIVED");
        assertThat(readTimestamp(conv.id(), "archived_at")).isNotNull();
        assertThat(readTimestamp(conv.id(), "closed_at")).isNotNull();    // preserved
    }

    @Test
    void closeConversation_underPartnerContext_succeeds() {
        // B2B2B: a partner may close a conversation belonging to one of its clients.
        Conversation conv = startForClientA("whatsapp", "+34612345678");

        TenantContext.setCurrentTenantId(partner.id());
        try {
            Conversation closed = conversationService.closeConversation(conv.id());
            assertThat(closed.status()).isEqualTo(ConversationStatus.CLOSED);
        } finally {
            TenantContext.clear();
        }
        assertThat(readStatus(conv.id())).isEqualTo("CLOSED");
    }

    @Test
    void closeConversation_whenAlreadyClosed_throwsAndLeavesRowUnchanged() {
        Conversation conv = startForClientA("whatsapp", "+34612345678");
        asClientA(() -> conversationService.closeConversation(conv.id()));
        Instant closedAt = readTimestamp(conv.id(), "closed_at");

        // A CLOSED conversation is never reopened or re-closed.
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            assertThatThrownBy(() -> conversationService.closeConversation(conv.id()))
                    .isInstanceOf(InvalidConversationTransitionException.class);
        } finally {
            TenantContext.clear();
        }

        assertThat(readStatus(conv.id())).isEqualTo("CLOSED");
        assertThat(readTimestamp(conv.id(), "closed_at")).isEqualTo(closedAt); // unchanged
    }

    private Conversation startForClientA(String channelType, String externalIdentityRef) {
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            return conversationService.startConversation(agent.id(), channelType, externalIdentityRef);
        } finally {
            TenantContext.clear();
        }
    }

    private void seedConversation(String channelType, String externalIdentityRef,
                                  ConversationStatus status) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO conversations (id, agent_id, channel_type, external_identity_ref, "
                + "status, started_at, last_message_at, closed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UuidGenerator.newV7(), agent.id(), channelType, externalIdentityRef,
                status.name(), Timestamp.from(now), Timestamp.from(now),
                status == ConversationStatus.CLOSED ? Timestamp.from(now) : null);
    }

    private void asClientA(Runnable action) {
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    private String readStatus(UUID conversationId) {
        return jdbc.queryForObject(
                "SELECT status FROM conversations WHERE id = ?", String.class, conversationId);
    }

    /** Reads a nullable timestamp column (column name is a test-controlled literal). */
    private Instant readTimestamp(UUID conversationId, String column) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT " + column + " FROM conversations WHERE id = ?", Timestamp.class, conversationId);
        return ts == null ? null : ts.toInstant();
    }

    private long countConversationsVisibleAs(UUID context) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE cauce_app");
                if (context != null) {
                    st.execute("SET LOCAL app.current_tenant_id = '" + context + "'");
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM conversations")) {
                    rs.next();
                    return rs.getLong(1);
                }
            } finally {
                conn.rollback();
            }
        }
    }
}
