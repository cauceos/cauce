package dev.cauce.api;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.api.support.FakeLlmProvider;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.worker.PendingInvocationWorker;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base of the business API ITs (ADR 0007): an operator, a partner and two businesses with their
 * keys, a scriptable model, and the worker run by hand so a test drives an agent turn by turn.
 */
@TestPropertySource(properties = {
        "cauce.orchestration.worker.enabled=true",
        "cauce.orchestration.worker.poll-interval-ms=3600000",
        "cauce.orchestration.worker.reaper.enabled=false",
        "cauce.llm.anthropic.enabled=false"
})
@Import(MessagingApiIT.FakeLlmConfig.class)
abstract class AbstractBusinessApiIT extends AbstractApiIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected TenantService tenantService;

    @Autowired
    protected ApiKeyService apiKeyService;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected PendingInvocationWorker worker;

    @Autowired
    protected FakeLlmProvider llm;

    protected UUID operatorId;
    protected UUID partnerId;
    protected UUID clientId;
    protected String partnerAuth;
    protected String clientAuth;
    protected String otherClientAuth;

    @BeforeEach
    void seedBusinesses() {
        truncateAll();
        TenantContext.clear();
        llm.reset();
        operatorId = tenantService.bootstrapOperator("Operator").id();
        partnerId = asTenant(operatorId, () -> tenantService.createPartner("Partner", operatorId).id());
        clientId = asTenant(partnerId, () -> tenantService.createClient("Bar", partnerId).id());
        UUID otherClientId = asTenant(partnerId, () -> tenantService.createClient("Otro bar", partnerId).id());
        partnerAuth = bearerFor(partnerId);
        clientAuth = bearerFor(clientId);
        otherClientAuth = bearerFor(otherClientId);
    }

    @AfterEach
    void resetModel() {
        llm.reset();
    }

    protected ResultActions putJson(String auth, String path, String json) throws Exception {
        return mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions getAs(String auth, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, auth));
    }

    protected JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected UUID createAgent(UUID tenantId, String auth, List<String> tools) throws Exception {
        String body = mockMvc.perform(post("/v1/tenants/" + tenantId + "/agents").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Camarero",
                                "system_prompt", "Eres el camarero.", "model_provider", "anthropic",
                                "model_name", "claude-sonnet-4-7", "tools", tools))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    /**
     * Posts {@code content} as {@code person} over the {@code api} channel, runs the worker and waits
     * for the invocation to complete; returns the conversation id.
     */
    protected UUID sendAndProcess(UUID agentId, String person, String content) throws Exception {
        String body = mockMvc.perform(post("/v1/agents/" + agentId + "/messages").header(HttpHeaders.AUTHORIZATION, clientAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("external_identity_ref", person,
                                "content", content))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String invocationId = objectMapper.readTree(body).get("invocation_id").asText();
        worker.pollAndProcess();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                getAs(clientAuth, "/v1/invocations/" + invocationId)
                        .andExpect(jsonPath("$.status").value("COMPLETED")));
        return UUID.fromString(objectMapper.readTree(body).get("conversation_id").asText());
    }

    /** The outputs of the TOOL_RESULT messages of a conversation, in order. */
    protected List<String> toolOutputs(UUID conversationId) throws Exception {
        JsonNode messages = json(getAs(clientAuth, "/v1/conversations/" + conversationId + "/messages?limit=200")
                .andExpect(status().isOk()));
        List<String> outputs = new java.util.ArrayList<>();
        messages.get("data").forEach(message -> {
            if ("TOOL_RESULT".equals(message.get("role").asText())) {
                outputs.add(message.at("/parts/0/output").asText());
            }
        });
        return outputs;
    }

    protected String bearerFor(UUID tenantId) {
        return "Bearer " + asTenant(tenantId, () -> apiKeyService.createApiKey(tenantId, "it-key").plaintextKey());
    }

    protected static <T> T asTenant(UUID tenantId, Supplier<T> action) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }
}
