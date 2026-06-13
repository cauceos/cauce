package dev.cauce.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.llm.exception.LlmRateLimitException;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import dev.cauce.orchestration.support.AbstractOrchestrationIntegrationTest;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.MessageService;
import dev.cauce.tenancy.TenantService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end tests for OrchestratorService using a {@link MockLlmProvider} (no real network
 * call). The Spring datasource connects as the container superuser (mirroring the app), so
 * RLS filtering of persisted data is verified through a dedicated restricted role, while the
 * service-level path is exercised across hierarchy levels.
 */
@Import(OrchestratorServiceIT.MockProviderConfig.class)
class OrchestratorServiceIT extends AbstractOrchestrationIntegrationTest {

    @TestConfiguration
    static class MockProviderConfig {
        @Bean
        MockLlmProvider mockLlmProvider() {
            return new MockLlmProvider();
        }
    }

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private OrchestratorService orchestratorService;

    @Autowired
    private MockLlmProvider mockLlmProvider;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant partner2;
    private Tenant clientA;
    private Agent agent;
    private Conversation conversation;
    private Message triggerMessage;

    @BeforeEach
    void setUp() {
        // Raw setup/introspection runs as the owner; the service runs as cauce_app, and
        // countConversationsVisibleAs uses the cauce_app datasource to exercise RLS for real.
        jdbc = new JdbcTemplate(adminDataSource);
        jdbc.execute("TRUNCATE TABLE api_keys, pending_invocations, messages, conversations, agents, tenants CASCADE");
        mockLlmProvider.respondWith(invocation ->
                new LlmResponse("default reply", List.of(), FinishReason.STOP, LlmUsage.of(1, 1)));
        TenantContext.clear();

        operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        partner2 = tenantService.createPartner("Partner 2", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        clientA = tenantService.createClient("Client A", partner.id());
        TenantContext.setCurrentTenantId(clientA.id());
        agent = agentService.createAgent(clientA.id(), "DentalBot",
                "You are a dentist receptionist.", "anthropic", "claude-sonnet-4-7");
        conversation = conversationService.startConversation(agent.id(), "whatsapp", "+34612345678");
        triggerMessage = messageService.appendMessage(conversation.id(), MessageRole.USER, "Hola");
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void respondToMessage_happyPath_persistsAgentReplyAndAdvancesConversation() {
        mockLlmProvider.respondWith(invocation ->
                new LlmResponse("Hola, soy un agente", List.of(), FinishReason.STOP, LlmUsage.of(8, 4)));
        Instant before = conversation.lastMessageAt();

        Message reply = respondAs(clientA.id());

        assertThat(reply.role()).isEqualTo(MessageRole.AGENT);
        assertThat(reply.content()).isEqualTo("Hola, soy un agente");
        Integer agentMessages = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND role = 'AGENT'",
                Integer.class, conversation.id());
        assertThat(agentMessages).isEqualTo(1);
        Instant dbLastMessageAt = jdbc.queryForObject(
                "SELECT last_message_at FROM conversations WHERE id = ?",
                Timestamp.class, conversation.id()).toInstant();
        assertThat(dbLastMessageAt).isAfter(before);
    }

    @Test
    void respondToMessage_whenLlmFails_persistsErrorMessageAndRethrows() {
        mockLlmProvider.respondWith(invocation -> {
            throw new LlmRateLimitException("anthropic", "claude-sonnet-4-7", "429 throttled");
        });

        assertThatThrownBy(() -> respondAs(clientA.id()))
                .isInstanceOf(LlmRateLimitException.class);

        String errorContent = jdbc.queryForObject(
                "SELECT content FROM messages WHERE conversation_id = ? AND role = 'SYSTEM'",
                String.class, conversation.id());
        assertThat(errorContent).startsWith("[orchestration_error] LlmRateLimitException: ");
        Integer agentMessages = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND role = 'AGENT'",
                Integer.class, conversation.id());
        assertThat(agentMessages).isZero(); // no agent reply persisted on failure
    }

    @Test
    void respondToMessage_asPartnerForItsClient_succeedsAndResultIsRlsFiltered() throws SQLException {
        mockLlmProvider.respondWith(invocation ->
                new LlmResponse("reply", List.of(), FinishReason.STOP, LlmUsage.of(1, 1)));

        // A partner can orchestrate on behalf of its own client.
        Message reply = respondAs(partner.id());
        assertThat(reply.role()).isEqualTo(MessageRole.AGENT);

        // The conversation (and its messages) are visible up the hierarchy, not to a sibling partner.
        assertThat(countConversationsVisibleAs(clientA.id())).isEqualTo(1);
        assertThat(countConversationsVisibleAs(partner.id())).isEqualTo(1);
        assertThat(countConversationsVisibleAs(operator.id())).isEqualTo(1);
        assertThat(countConversationsVisibleAs(partner2.id())).isZero();
    }

    @Test
    void respondToMessage_whenModelCallsTool_runsLoopAndPersistsToolMessagesVisibleViaApi() {
        // First invocation asks for the clock; once the tool result is in context, reply with text.
        mockLlmProvider.respondWith(invocation -> {
            boolean afterTool = invocation.messages().stream()
                    .anyMatch(message -> message.toolContent() instanceof ToolResult);
            if (afterTool) {
                return new LlmResponse("The current time has been retrieved.", List.of(),
                        FinishReason.STOP, LlmUsage.of(5, 5));
            }
            return new LlmResponse("", List.of(new ToolCall("call-1", "get_current_time", Map.of())),
                    FinishReason.TOOL_USE, LlmUsage.of(3, 3));
        });

        Message reply = respondAs(clientA.id());

        assertThat(reply.role()).isEqualTo(MessageRole.AGENT);
        assertThat(reply.content()).isEqualTo("The current time has been retrieved.");

        List<String> roles = jdbc.queryForList(
                "SELECT role FROM messages WHERE conversation_id = ? ORDER BY created_at ASC, id ASC",
                String.class, conversation.id());
        assertThat(roles).containsExactly("USER", "TOOL_CALL", "TOOL_RESULT", "AGENT");

        // The tool messages carry their structured payload (jsonb), persisted and API-visible.
        Integer toolMessagesWithContent = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ? "
                        + "AND role IN ('TOOL_CALL', 'TOOL_RESULT') AND tool_content IS NOT NULL",
                Integer.class, conversation.id());
        assertThat(toolMessagesWithContent).isEqualTo(2);
    }

    @Test
    void respondToMessage_whenConversationExceedsWindow_invokesLlmWithTruncatedContext() {
        // Insert 100 USER messages of 7000 chars (2000 tokens each); 95 fit the 190_000 window.
        seedLargeHistory(100, 7000);
        UUID trigger = lastUserMessageId();

        mockLlmProvider.respondWith(invocation ->
                new LlmResponse("reply", List.of(), FinishReason.STOP, LlmUsage.of(1, 1)));
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            orchestratorService.respondToMessage(conversation.id(), trigger);
        } finally {
            TenantContext.clear();
        }

        List<dev.cauce.llm.model.LlmMessage> sent = mockLlmProvider.lastInvocation().messages();
        assertThat(sent).hasSize(95);
        assertThat(sent.get(0).content()).startsWith("MSG5:");
        assertThat(sent.get(94).content()).startsWith("MSG99:");
    }

    private Message respondAs(UUID context) {
        TenantContext.setCurrentTenantId(context);
        try {
            return orchestratorService.respondToMessage(conversation.id(), triggerMessage.id());
        } finally {
            TenantContext.clear();
        }
    }

    private void seedLargeHistory(int count, int chars) {
        List<Object[]> rows = new ArrayList<>();
        Instant base = Instant.now();
        for (int i = 0; i < count; i++) {
            String prefix = "MSG" + i + ":";
            String content = prefix + "x".repeat(chars - prefix.length());
            rows.add(new Object[]{UUID.randomUUID(), conversation.id(), "USER", content,
                    Timestamp.from(base.plusMillis(i))});
        }
        jdbc.batchUpdate("INSERT INTO messages (id, conversation_id, role, content, created_at) "
                + "VALUES (?, ?, ?, ?, ?)", rows);
    }

    private UUID lastUserMessageId() {
        return jdbc.queryForObject(
                "SELECT id FROM messages WHERE conversation_id = ? ORDER BY created_at DESC LIMIT 1",
                UUID.class, conversation.id());
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
