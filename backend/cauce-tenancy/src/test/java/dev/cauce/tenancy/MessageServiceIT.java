package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tenant.MissingTenantContextException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests for MessageService and the messages RLS policy. The Spring datasource
 * connects as the container superuser (mirroring the current app), so RLS filtering is
 * verified through a dedicated restricted role on data created by the service. Visibility
 * is inherited transitively: a message is visible iff its conversation is visible iff its
 * agent is visible iff the agent's tenant is visible.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MessageServiceIT {

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
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant clientA;
    private Tenant clientB;
    private Agent agent;             // owned by clientA
    private Conversation conversation; // of agent

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
        TenantContext.setCurrentTenantId(clientA.id());
        agent = agentService.createAgent(clientA.id(), "DentalBot",
                "You are a dentist receptionist.", "anthropic", "claude-sonnet-4-7");
        conversation = conversationService.startConversation(agent.id(), "whatsapp", "+34612345678");
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void appendMessage_persistsMessage() {
        Message message = appendForClientA(MessageRole.USER, "I need an appointment");

        assertThat(message.conversationId()).isEqualTo(conversation.id());
        assertThat(message.role()).isEqualTo(MessageRole.USER);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE id = ?", Integer.class, message.id());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void appendMessage_advancesConversationLastMessageAt() throws InterruptedException {
        Instant originalLastMessageAt = conversation.lastMessageAt();
        Thread.sleep(5); // ensure the clock advances past the conversation's start

        Message message = appendForClientA(MessageRole.USER, "Hello");

        Instant dbLastMessageAt = jdbc.queryForObject(
                "SELECT last_message_at FROM conversations WHERE id = ?",
                Timestamp.class, conversation.id()).toInstant();
        assertThat(dbLastMessageAt).isAfter(originalLastMessageAt);
        assertThat(dbLastMessageAt.truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(message.createdAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void listMessages_returnsMessagesInChronologicalOrder() throws InterruptedException {
        List<UUID> appended = new ArrayList<>();
        for (String body : List.of("first", "second", "third")) {
            appended.add(appendForClientA(MessageRole.USER, body).id());
            Thread.sleep(2); // distinct created_at for deterministic ordering
        }

        TenantContext.setCurrentTenantId(clientA.id());
        try {
            List<Message> messages = messageService.listMessages(conversation.id());
            assertThat(messages).extracting(Message::id).containsExactlyElementsOf(appended);
            assertThat(messages).extracting(Message::createdAt).isSorted();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void messagesCreatedViaService_areFilteredByRlsHierarchy() throws SQLException {
        appendForClientA(MessageRole.USER, "Hello");

        assertThat(countMessagesVisibleAs(clientA.id())).isEqualTo(1);  // owner sees it
        assertThat(countMessagesVisibleAs(partner.id())).isEqualTo(1);  // its partner sees it
        assertThat(countMessagesVisibleAs(operator.id())).isEqualTo(1); // its operator sees it
        assertThat(countMessagesVisibleAs(clientB.id())).isZero();      // sibling client does not
        assertThat(countMessagesVisibleAs(null)).isZero();             // no context => nothing
    }

    @Test
    void appendMessage_withoutContext_throwsMissingTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() ->
                messageService.appendMessage(conversation.id(), MessageRole.USER, "Hi"))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void uuidV7Ordering_whenAppendingMultipleMessages_thenIdsAreTimeOrdered()
            throws InterruptedException {
        List<UUID> creationOrder = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            creationOrder.add(appendForClientA(MessageRole.USER, "msg " + i).id());
            Thread.sleep(2);
        }

        List<UUID> byId = jdbc.queryForList("SELECT id FROM messages ORDER BY id", UUID.class);

        assertThat(byId).containsExactlyElementsOf(creationOrder);
    }

    private Message appendForClientA(MessageRole role, String content) {
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            return messageService.appendMessage(conversation.id(), role, content);
        } finally {
            TenantContext.clear();
        }
    }

    private long countMessagesVisibleAs(UUID context) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE cauce_app");
                if (context != null) {
                    st.execute("SET LOCAL app.current_tenant_id = '" + context + "'");
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM messages")) {
                    rs.next();
                    return rs.getLong(1);
                }
            } finally {
                conn.rollback();
            }
        }
    }
}
