package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end check that the {@code cauce-observability} metrics listener observes the real
 * event stream: one message driven through ingest → worker → agentic loop moves the
 * Micrometer counters. Runs in cauce-api because it is the only module whose Spring context
 * joins the orchestrator (publisher), the listener, and the Actuator-provided
 * {@link MeterRegistry}. Reuses the {@link MessagingApiIT} context (same properties and
 * imported config) so no extra application context is booted.
 *
 * <p>All assertions are deltas: the context — and so the registry — is cached and shared
 * across cauce-api ITs, making absolute counter values order-dependent.
 */
@TestPropertySource(properties = {
        "cauce.orchestration.worker.enabled=true",
        "cauce.orchestration.worker.poll-interval-ms=3600000",
        "cauce.orchestration.worker.reaper.enabled=false",
        "cauce.llm.anthropic.enabled=false"
})
@Import(MessagingApiIT.FakeLlmConfig.class)
class OrchestrationMetricsIT extends AbstractApiIntegrationTest {

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
    private MeterRegistry meterRegistry;

    private String clientAuth;
    private UUID agentId;

    @BeforeEach
    void setUp() throws Exception {
        truncateAll();
        TenantContext.clear();

        UUID operatorId = tenantService.bootstrapOperator("Operator").id();
        String operatorAuth = bearerFor(operatorId);
        String partnerBody = mockMvc.perform(postAs(operatorAuth, "/v1/tenants/partner",
                        new CreatePartnerRequest("Partner", operatorId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID partnerId = UUID.fromString(objectMapper.readTree(partnerBody).get("id").asText());
        String clientBody = mockMvc.perform(postAs(bearerFor(partnerId), "/v1/tenants/client",
                        new CreateClientRequest("Client", partnerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID clientId = UUID.fromString(objectMapper.readTree(clientBody).get("id").asText());
        clientAuth = bearerFor(clientId);
        String agentBody = mockMvc.perform(postAs(clientAuth, "/v1/tenants/" + clientId + "/agents",
                        new CreateAgentRequest("Bot", "You are helpful", "anthropic",
                                "claude-sonnet-4-7", null, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        agentId = UUID.fromString(objectMapper.readTree(agentBody).get("id").asText());
    }

    @Test
    void ingestAndProcess_oneMessage_movesTheOrchestrationCounters() throws Exception {
        double requestedBefore = counter("cauce.orchestration.invocations.requested");
        double completedBefore = counter("cauce.orchestration.invocations.completed");
        double responsesBefore = counter("cauce.orchestration.llm.responses",
                "provider", "anthropic", "model", "claude-sonnet-4-7", "finish_reason", "STOP");
        double inputTokensBefore = tokenCounter("input");
        double outputTokensBefore = tokenCounter("output");

        mockMvc.perform(postAs(clientAuth, "/v1/agents/" + agentId + "/messages",
                        new PostMessageRequest("user-1", "Hola")))
                .andExpect(status().isAccepted());
        worker.pollAndProcess();

        // Processing runs on the worker's executor; InvocationCompleted is the last event of
        // the loop, so once it lands every earlier meter update has happened too.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(counter("cauce.orchestration.invocations.completed"))
                        .isEqualTo(completedBefore + 1));

        assertThat(counter("cauce.orchestration.invocations.requested"))
                .isEqualTo(requestedBefore + 1);
        assertThat(counter("cauce.orchestration.llm.responses",
                "provider", "anthropic", "model", "claude-sonnet-4-7", "finish_reason", "STOP"))
                .isEqualTo(responsesBefore + 1);
        // FakeLlmProvider reports LlmUsage.of(1, 1) per call.
        assertThat(tokenCounter("input")).isEqualTo(inputTokensBefore + 1);
        assertThat(tokenCounter("output")).isEqualTo(outputTokensBefore + 1);
    }

    private double counter(String name, String... tags) {
        return meterRegistry.counter(name, tags).count();
    }

    private double tokenCounter(String kind) {
        return counter("cauce.orchestration.llm.tokens",
                "provider", "anthropic", "model", "claude-sonnet-4-7", "kind", kind);
    }

    private String bearerFor(UUID tenantId) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return "Bearer " + apiKeyService.createApiKey(tenantId, "it-key").plaintextKey();
        } finally {
            TenantContext.clear();
        }
    }

    private MockHttpServletRequestBuilder postAs(String auth, String path, Object body)
            throws Exception {
        return post(path)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }
}
