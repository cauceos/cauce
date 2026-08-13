package dev.cauce.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import dev.cauce.orchestration.service.ConversationGateway;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.TenantService;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end test of the enriched message DTO: TOOL_CALL / TOOL_RESULT messages carry the
 * structured {@code tool_content} (call → {@code input}; result → {@code output} + {@code
 * is_error}), and text messages carry none. The tool round is seeded directly through the
 * persistence services (no live LLM), then read back over the real messages endpoint.
 */
class MessageToolContentApiIT extends AbstractApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationGateway conversationGateway;

    private UUID clientId;
    private String clientAuth;
    private UUID conversationId;

    @BeforeEach
    void setUp() {
        truncateAll();
        TenantContext.clear();

        UUID operatorId = tenantService.bootstrapOperator("Operator").id();
        UUID partnerId = asTenant(operatorId, () -> tenantService.createPartner("Partner", operatorId).id());
        clientId = asTenant(partnerId, () -> tenantService.createClient("Client", partnerId).id());
        clientAuth = bearerFor(clientId);

        conversationId = asTenant(clientId, () -> {
            UUID agentId = agentService
                    .createAgent(clientId, "Bot", "You are helpful", "anthropic", "claude-opus-4-8", null, null)
                    .id();
            Conversation conversation = conversationService.startConversation(agentId, "api", "user-1");
            UUID convId = conversation.id();
            // A full tool round: user asks, agent calls a tool, the tool answers.
            conversationGateway.append(Message.from(convId, MessageRole.USER, "What time is it?"));
            conversationGateway.append(Message.toolCall(convId,
                    new ToolCall("call-1", "get_current_time", Map.of("timezone", "UTC"))));
            conversationGateway.append(Message.toolResult(convId,
                    ToolResult.success("call-1", "get_current_time", "2026-08-13T10:00:00Z")));
            return convId;
        });
    }

    @Test
    void listMessages_toolRound_exposesStructuredToolContent() throws Exception {
        mockMvc.perform(get("/v1/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, clientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                // Text message: no tool_content at all.
                .andExpect(jsonPath("$.data[0].role").value("USER"))
                .andExpect(jsonPath("$.data[0].content").value("What time is it?"))
                .andExpect(jsonPath("$.data[0].tool_content").doesNotExist())
                // TOOL_CALL: input present; result-only fields absent.
                .andExpect(jsonPath("$.data[1].role").value("TOOL_CALL"))
                .andExpect(jsonPath("$.data[1].content").value("get_current_time"))
                .andExpect(jsonPath("$.data[1].tool_content.tool_call_id").value("call-1"))
                .andExpect(jsonPath("$.data[1].tool_content.tool_name").value("get_current_time"))
                .andExpect(jsonPath("$.data[1].tool_content.input.timezone").value("UTC"))
                .andExpect(jsonPath("$.data[1].tool_content.output").doesNotExist())
                .andExpect(jsonPath("$.data[1].tool_content.is_error").doesNotExist())
                // TOOL_RESULT: output + is_error present; input absent.
                .andExpect(jsonPath("$.data[2].role").value("TOOL_RESULT"))
                .andExpect(jsonPath("$.data[2].tool_content.tool_call_id").value("call-1"))
                .andExpect(jsonPath("$.data[2].tool_content.output").value("2026-08-13T10:00:00Z"))
                .andExpect(jsonPath("$.data[2].tool_content.is_error").value(false))
                .andExpect(jsonPath("$.data[2].tool_content.input").doesNotExist());
    }

    // --- helpers ---

    private String bearerFor(UUID tenantId) {
        return "Bearer " + asTenant(tenantId, () -> apiKeyService.createApiKey(tenantId, "it-key").plaintextKey());
    }

    private <T> T asTenant(UUID tenantId, Supplier<T> action) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }
}
