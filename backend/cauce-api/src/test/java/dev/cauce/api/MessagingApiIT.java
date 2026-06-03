package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.agent.CreateAgentRequest;
import dev.cauce.api.message.PostMessageRequest;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.api.support.FakeLlmProvider;
import dev.cauce.api.tenant.CreateClientRequest;
import dev.cauce.api.tenant.CreatePartnerRequest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.worker.PendingInvocationWorker;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end test of the public messaging surface — the first test of the full public loop with
 * the invocation engine. A USER message is POSTed to an agent (202), the worker is driven with a
 * {@link FakeLlmProvider} standing in for the LLM adapter, and the agent's reply is read back via
 * the messages GET. Also covers resolve-or-create reuse, enqueue, hierarchical authority (404),
 * and conversation status — all over HTTP, with the tenant derived from the validated API key.
 *
 * <p>The worker is enabled here (overriding the module test default) with a one-hour scheduler
 * interval, so the only processing is the deterministic manual {@code pollAndProcess()}. The real
 * Anthropic adapter is disabled so the {@link FakeLlmProvider} is the sole provider in the
 * registry (no id collision).
 */
@TestPropertySource(properties = {
        "cauce.orchestration.worker.enabled=true",
        "cauce.orchestration.worker.poll-interval-ms=3600000",
        "cauce.orchestration.worker.reaper.enabled=false",
        "cauce.llm.anthropic.enabled=false"
})
@Import(MessagingApiIT.FakeLlmConfig.class)
class MessagingApiIT extends AbstractApiIntegrationTest {

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        FakeLlmProvider fakeLlmProvider() {
            return new FakeLlmProvider();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PendingInvocationWorker worker;

    private JdbcTemplate jdbc;

    private UUID operatorId;
    private String operatorAuth;
    private UUID clientId;
    private String clientAuth;
    private UUID agentId;

    @BeforeEach
    void setUp() throws Exception {
        truncateAll();
        TenantContext.clear();
        jdbc = new JdbcTemplate(adminDataSource);

        operatorId = tenantService.bootstrapOperator("Operator").id();
        operatorAuth = bearerFor(operatorId);
        UUID partnerId = createPartner();
        clientId = createClient(bearerFor(partnerId), partnerId);
        clientAuth = bearerFor(clientId);
        agentId = createAgent(clientAuth, clientId);
    }

    @Test
    void postMessage_thenWorkerProcesses_thenGetShowsUserThenAgent() throws Exception {
        UUID conversationId = postMessage(clientAuth, agentId, "user-1", "Hola");

        // The worker (driven manually) processes the queued invocation with the fake provider.
        worker.pollAndProcess();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(agentMessageCount(conversationId)).isEqualTo(1));

        // The poller reads the full thread: USER first, then the AGENT reply, in chronological order.
        mockMvc.perform(getAs(clientAuth, "/v1/conversations/" + conversationId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].content").value("Hola"))
                .andExpect(jsonPath("$[1].role").value("AGENT"))
                .andExpect(jsonPath("$[1].content").value(FakeLlmProvider.REPLY));
    }

    @Test
    void postMessage_returns202AndLeavesAPendingInvocation() throws Exception {
        UUID conversationId = postMessage(clientAuth, agentId, "user-1", "Hola");

        // Before the worker runs, the invocation is queued PENDING for this conversation.
        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM pending_invocations WHERE conversation_id = ? AND status = 'PENDING'",
                Integer.class, conversationId);
        assertThat(pending).isEqualTo(1);
    }

    @Test
    void postMessage_resolveOrCreate_sameIdentityReuses_differentIdentityStartsNew() throws Exception {
        UUID first = postMessage(clientAuth, agentId, "user-1", "Hola");
        UUID again = postMessage(clientAuth, agentId, "user-1", "¿Sigues ahí?");
        assertThat(again).isEqualTo(first);

        UUID other = postMessage(clientAuth, agentId, "user-2", "Hello");
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void postMessage_toAgentNotVisibleToCaller_returns404() throws Exception {
        // A sibling partner with its own client/agent has no path to this caller's agent.
        UUID partnerB = createPartner();
        UUID clientB = createClient(bearerFor(partnerB), partnerB);
        String clientBAuth = bearerFor(clientB);

        mockMvc.perform(postAs(clientBAuth, "/v1/agents/" + agentId + "/messages",
                        new PostMessageRequest("user-1", "Hola")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("agent_not_found"));
    }

    @Test
    void getMessages_forConversationNotVisibleToCaller_returns404() throws Exception {
        UUID conversationId = postMessage(clientAuth, agentId, "user-1", "Hola");

        UUID partnerB = createPartner();
        UUID clientB = createClient(bearerFor(partnerB), partnerB);
        String clientBAuth = bearerFor(clientB);

        mockMvc.perform(getAs(clientBAuth, "/v1/conversations/" + conversationId + "/messages"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("conversation_not_found"));
    }

    @Test
    void getConversation_returnsStatusToOwner_and404ToNonVisibleCaller() throws Exception {
        UUID conversationId = postMessage(clientAuth, agentId, "user-1", "Hola");

        mockMvc.perform(getAs(clientAuth, "/v1/conversations/" + conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversationId.toString()))
                .andExpect(jsonPath("$.agent_id").value(agentId.toString()))
                .andExpect(jsonPath("$.channel_type").value("api"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        UUID partnerB = createPartner();
        UUID clientB = createClient(bearerFor(partnerB), partnerB);
        mockMvc.perform(getAs(bearerFor(clientB), "/v1/conversations/" + conversationId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("conversation_not_found"));
    }

    @Test
    void postMessage_withBlankContent_returns400() throws Exception {
        mockMvc.perform(postAs(clientAuth, "/v1/agents/" + agentId + "/messages",
                        new PostMessageRequest("user-1", "  ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("content"));
    }

    // --- helpers ---

    /** POSTs a USER message and returns the conversation id from the 202 body. */
    private UUID postMessage(String auth, UUID agent, String externalIdentityRef, String content)
            throws Exception {
        String body = mockMvc.perform(postAs(auth, "/v1/agents/" + agent + "/messages",
                        new PostMessageRequest(externalIdentityRef, content)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.conversation_id").exists())
                .andExpect(jsonPath("$.message_id").exists())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("conversation_id").asText());
    }

    private Integer agentMessageCount(UUID conversationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND role = 'AGENT'",
                Integer.class, conversationId);
    }

    private String bearerFor(UUID tenantId) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return "Bearer " + apiKeyService.createApiKey(tenantId, "it-key").plaintextKey();
        } finally {
            TenantContext.clear();
        }
    }

    private MockHttpServletRequestBuilder postAs(String auth, String path, Object body) throws Exception {
        return post(path)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder getAs(String auth, String path) {
        return get(path).header(HttpHeaders.AUTHORIZATION, auth);
    }

    private UUID createPartner() throws Exception {
        String body = mockMvc.perform(postAs(operatorAuth, "/v1/tenants/partner",
                        new CreatePartnerRequest("Partner", operatorId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private UUID createClient(String partnerAuth, UUID partnerId) throws Exception {
        String body = mockMvc.perform(postAs(partnerAuth, "/v1/tenants/client",
                        new CreateClientRequest("Client", partnerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private UUID createAgent(String auth, UUID tenantId) throws Exception {
        // Use a model the orchestrator's ModelContextWindow knows, so the worker can build the
        // context and the loop completes. FakeLlmProvider supports any claude-* model.
        String body = mockMvc.perform(postAs(auth, "/v1/tenants/" + tenantId + "/agents",
                        new CreateAgentRequest("Bot", "You are helpful", "anthropic", "claude-sonnet-4-7", null, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }
}
