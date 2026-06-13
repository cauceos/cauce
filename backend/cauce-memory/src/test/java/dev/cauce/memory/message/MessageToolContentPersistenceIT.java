package dev.cauce.memory.message;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.memory.agent.AgentMapper;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.conversation.ConversationMapper;
import dev.cauce.memory.conversation.ConversationRepository;
import dev.cauce.memory.tenant.TenantMapper;
import dev.cauce.memory.tenant.TenantRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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
 * End-to-end persistence tests for tool messages (V14): the structured tool payload round-trips
 * through the jsonb {@code tool_content} column via Hibernate with the {@code tool_call_id}
 * correlation intact, and Row-Level Security still filters tool messages by the conversation's
 * tenant hierarchy. RLS is exercised through the least-privilege {@code cauce_app} role (the
 * test superuser bypasses RLS, so it is used only to seed).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MessageToolContentPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    private final TenantMapper tenantMapper = new TenantMapper();
    private final AgentMapper agentMapper = new AgentMapper();
    private final ConversationMapper conversationMapper = new ConversationMapper();
    private final MessageMapper messageMapper = new MessageMapper();

    private UUID operatorId;
    private UUID partnerId;
    private UUID clientId;
    private UUID clientBId;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        new JdbcTemplate(dataSource).execute("TRUNCATE TABLE api_keys, pending_invocations, "
                + "messages, conversations, agents, tenants CASCADE");

        Tenant operator = Tenant.operator("Operator");
        Tenant partner = Tenant.partner("Partner", operator.id());
        Tenant clientA = Tenant.client("Client A", partner.id());
        Tenant clientB = Tenant.client("Client B", partner.id());
        tenantRepository.save(tenantMapper.toEntity(operator));
        tenantRepository.save(tenantMapper.toEntity(partner));
        tenantRepository.save(tenantMapper.toEntity(clientA));
        tenantRepository.save(tenantMapper.toEntity(clientB));
        operatorId = operator.id();
        partnerId = partner.id();
        clientId = clientA.id();
        clientBId = clientB.id();

        Agent agent = Agent.create(clientA.id(), "Agent", "", "ollama", "llama3");
        agentRepository.save(agentMapper.toEntity(agent));

        conversation = Conversation.start(agent.id(), "api", "user-1");
        conversationRepository.save(conversationMapper.toEntity(conversation));
    }

    @Test
    void toolCallAndResult_roundTripThroughJsonb_withCorrelationIdIntact() {
        ToolCall call = new ToolCall("call-1", "get_current_time", Map.of("tz", "UTC"));
        ToolResult result =
                ToolResult.success("call-1", "get_current_time", "2026-06-13T10:15:30Z");
        messageRepository.save(messageMapper.toEntity(Message.toolCall(conversation.id(), call)));
        messageRepository.save(
                messageMapper.toEntity(Message.toolResult(conversation.id(), result)));

        List<Message> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversation.id())
                .stream()
                .map(messageMapper::toDomain)
                .toList();

        assertThat(messages).hasSize(2);

        Message persistedCall = messages.get(0);
        assertThat(persistedCall.role()).isEqualTo(MessageRole.TOOL_CALL);
        assertThat(persistedCall.toolContent()).contains(call);

        Message persistedResult = messages.get(1);
        assertThat(persistedResult.role()).isEqualTo(MessageRole.TOOL_RESULT);
        assertThat(persistedResult.toolContent()).contains(result);

        // The result correlates back to its call by tool_call_id, surviving the jsonb round-trip.
        assertThat(persistedResult.toolContent().orElseThrow().toolCallId())
                .isEqualTo(persistedCall.toolContent().orElseThrow().toolCallId());
    }

    @Test
    void toolMessages_areFilteredByRlsHierarchy() throws SQLException {
        messageRepository.save(messageMapper.toEntity(Message.toolCall(conversation.id(),
                new ToolCall("call-1", "get_current_time", Map.of()))));

        assertThat(countMessagesVisibleAs(clientId)).isEqualTo(1);   // owning client
        assertThat(countMessagesVisibleAs(partnerId)).isEqualTo(1);  // ancestor partner sees it
        assertThat(countMessagesVisibleAs(operatorId)).isEqualTo(1); // ancestor operator sees it
        assertThat(countMessagesVisibleAs(clientBId)).isZero();      // sibling client blocked
        assertThat(countMessagesVisibleAs(null)).isZero();           // no context => nothing
    }

    /**
     * Counts visible messages as the least-privilege role under the given tenant context, so RLS
     * actually applies. A null context sets nothing.
     */
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
                conn.rollback(); // reverts SET ROLE and SET LOCAL; cleans the pooled connection
            }
        }
    }
}
