package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.agent.CreateAgentRequest;
import dev.cauce.api.message.PostMessageRequest;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.api.support.FakeLlmProvider;
import dev.cauce.api.tenant.CreateClientRequest;
import dev.cauce.api.tenant.CreatePartnerRequest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.llm.exception.LlmInvalidRequestException;
import dev.cauce.orchestration.worker.PendingInvocationWorker;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end test of the invocation-status surface: the 202 of the messaging endpoint carries
 * the invocation id (and an idempotent replay carries the same one), and
 * {@code GET /v1/invocations/{id}} tracks the row through its lifecycle — including the gap
 * this closes: a permanently failed invocation leaves a client-visible signal with a public
 * failure reason and no raw provider detail. Same harness as {@link MessagingApiIT}: manual
 * worker, {@link FakeLlmProvider}, real RLS via the {@code cauce_app} role.
 */
@TestPropertySource(properties = {
        "cauce.orchestration.worker.enabled=true",
        "cauce.orchestration.worker.poll-interval-ms=3600000",
        "cauce.orchestration.worker.reaper.enabled=false",
        "cauce.llm.anthropic.enabled=false"
})
@Import(MessagingApiIT.FakeLlmConfig.class)
class InvocationApiIT extends AbstractApiIntegrationTest {

    private static final String PROVIDER_DETAIL = "400 bad request: tool schema rejected by provider";

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

    @Autowired
    private FakeLlmProvider fakeLlmProvider;

    private JdbcTemplate jdbc;

    private UUID operatorId;
    private String operatorAuth;
    private String clientAuth;
    private UUID agentId;

    @BeforeEach
    void setUp() throws Exception {
        truncateAll();
        TenantContext.clear();
        fakeLlmProvider.reset();
        jdbc = new JdbcTemplate(adminDataSource);

        operatorId = tenantService.bootstrapOperator("Operator").id();
        operatorAuth = bearerFor(operatorId);
        UUID partnerId = createPartner();
        UUID clientId = createClient(bearerFor(partnerId), partnerId);
        clientAuth = bearerFor(clientId);
        agentId = createAgent(clientAuth, clientId);
    }

    @AfterEach
    void tearDown() {
        // The provider bean is cached in the shared application context across IT classes.
        fakeLlmProvider.reset();
    }

    @Test
    void postMessage_202CarriesInvocationId_andGetReturnsPending() throws Exception {
        JsonNode accepted = postMessage(clientAuth, "user-1", "Hola");
        UUID invocationId = UUID.fromString(accepted.get("invocation_id").asText());

        mockMvc.perform(getAs(clientAuth, "/v1/invocations/" + invocationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(invocationId.toString()))
                .andExpect(jsonPath("$.conversation_id")
                        .value(accepted.get("conversation_id").asText()))
                .andExpect(jsonPath("$.trigger_message_id")
                        .value(accepted.get("message_id").asText()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.failure_reason").value((Object) null))
                .andExpect(jsonPath("$.completed_at").value((Object) null));
    }

    @Test
    void postMessage_replayWithSameIdempotencyKey_returnsSameInvocationId() throws Exception {
        PostMessageRequest request = new PostMessageRequest("user-1", "Hola");
        String first = mockMvc.perform(postAs(clientAuth, "/v1/agents/" + agentId + "/messages",
                        request).header("Idempotency-Key", "retry-inv-1"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String replay = mockMvc.perform(postAs(clientAuth, "/v1/agents/" + agentId + "/messages",
                        request).header("Idempotency-Key", "retry-inv-1"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String firstInvocation = objectMapper.readTree(first).get("invocation_id").asText();
        String replayInvocation = objectMapper.readTree(replay).get("invocation_id").asText();
        assertThat(replayInvocation).isEqualTo(firstInvocation);

        // And only one invocation row exists for the conversation.
        UUID conversationId = UUID.fromString(
                objectMapper.readTree(first).get("conversation_id").asText());
        Integer invocations = jdbc.queryForObject(
                "SELECT count(*) FROM pending_invocations WHERE conversation_id = ?",
                Integer.class, conversationId);
        assertThat(invocations).isEqualTo(1);
    }

    @Test
    void getInvocation_afterWorkerSucceeds_returnsCompleted() throws Exception {
        JsonNode accepted = postMessage(clientAuth, "user-1", "Hola");
        UUID invocationId = UUID.fromString(accepted.get("invocation_id").asText());

        worker.pollAndProcess();
        awaitTerminalStatus(invocationId, "COMPLETED");

        mockMvc.perform(getAs(clientAuth, "/v1/invocations/" + invocationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.failure_reason").value((Object) null))
                .andExpect(jsonPath("$.completed_at").exists());
    }

    @Test
    void getInvocation_afterProviderFailure_returnsFailedWithoutProviderDetail() throws Exception {
        fakeLlmProvider.failNextWith(new LlmInvalidRequestException(
                "anthropic", "claude-sonnet-4-7", PROVIDER_DETAIL));
        JsonNode accepted = postMessage(clientAuth, "user-1", "Hola");
        UUID invocationId = UUID.fromString(accepted.get("invocation_id").asText());

        worker.pollAndProcess();
        awaitTerminalStatus(invocationId, "FAILED");

        String body = mockMvc.perform(getAs(clientAuth, "/v1/invocations/" + invocationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure_reason").value("PROVIDER_ERROR"))
                .andExpect(jsonPath("$.last_error").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("the raw provider error must never reach the client")
                .doesNotContain("tool schema").doesNotContain("400 bad request");

        // The row itself did record the internal taxonomy and detail (for operators).
        assertThat(jdbc.queryForObject(
                "SELECT failure_type FROM pending_invocations WHERE id = ?",
                String.class, invocationId)).isEqualTo("LLM_ERROR");
    }

    @Test
    void getInvocation_byNonVisibleTenant_returns404InvocationNotFound() throws Exception {
        JsonNode accepted = postMessage(clientAuth, "user-1", "Hola");
        UUID invocationId = UUID.fromString(accepted.get("invocation_id").asText());

        // A sibling partner's client has no hierarchical path to this invocation.
        UUID partnerB = createPartner();
        UUID clientB = createClient(bearerFor(partnerB), partnerB);

        mockMvc.perform(getAs(bearerFor(clientB), "/v1/invocations/" + invocationId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("invocation_not_found"));

        // The operator above sees it (hierarchical visibility).
        mockMvc.perform(getAs(operatorAuth, "/v1/invocations/" + invocationId))
                .andExpect(status().isOk());
    }

    // --- helpers ---

    private void awaitTerminalStatus(UUID invocationId, String expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                jdbc.queryForObject("SELECT status FROM pending_invocations WHERE id = ?",
                        String.class, invocationId)).isEqualTo(expected));
    }

    private JsonNode postMessage(String auth, String externalIdentityRef, String content)
            throws Exception {
        String body = mockMvc.perform(postAs(auth, "/v1/agents/" + agentId + "/messages",
                        new PostMessageRequest(externalIdentityRef, content)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.invocation_id").exists())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
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
        // A model the orchestrator's ModelContextWindow knows; FakeLlmProvider takes claude-*.
        String body = mockMvc.perform(postAs(auth, "/v1/tenants/" + tenantId + "/agents",
                        new CreateAgentRequest("Bot", "You are helpful", "anthropic",
                                "claude-sonnet-4-7", null, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }
}
