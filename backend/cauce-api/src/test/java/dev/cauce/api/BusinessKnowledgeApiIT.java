package dev.cauce.api;

import static dev.cauce.api.support.FakeLlmProvider.finalReply;
import static dev.cauce.api.support.FakeLlmProvider.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.api.support.FakeLlmProvider;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import dev.cauce.orchestration.worker.PendingInvocationWorker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
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

/**
 * The business profile, the catalog and the unanswered questions over the API (ADR 0007, B4),
 * and the agent reading them through its tools: the endpoints' hierarchy rules, and a scripted
 * model whose catalog lookup returns NOT_DECLARED for an allergen the business did not state.
 */
@TestPropertySource(properties = {
        "cauce.orchestration.worker.enabled=true",
        "cauce.orchestration.worker.poll-interval-ms=3600000",
        "cauce.orchestration.worker.reaper.enabled=false",
        "cauce.llm.anthropic.enabled=false"
})
@Import(MessagingApiIT.FakeLlmConfig.class)
class BusinessKnowledgeApiIT extends AbstractApiIntegrationTest {

    private static final String PROFILE = """
            {"name": "Bar La Esquina", "time_zone": "Europe/Madrid",
             "facts": {"horario": "mar-dom 12:00-16:00", "terraza": "sí, 6 mesas"}}""";
    private static final String CATALOG = """
            {"declared_attributes": ["allergens"],
             "items": [
               {"ref": "bravas", "section": "Tapas", "name": "Patatas bravas", "price": 5.50,
                "attributes": {"allergens": ["huevo"], "vegetariano": true}},
               {"ref": "tarta-queso", "section": "Postres", "name": "Tarta de queso", "price": 5.00}]}""";

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
    private FakeLlmProvider llm;

    private UUID partnerId;
    private UUID clientId;
    private String partnerAuth;
    private String clientAuth;
    private String otherClientAuth;

    @BeforeEach
    void setUp() {
        truncateAll();
        TenantContext.clear();
        llm.reset();
        UUID operatorId = tenantService.bootstrapOperator("Operator").id();
        partnerId = asTenant(operatorId, () -> tenantService.createPartner("Partner", operatorId).id());
        clientId = asTenant(partnerId, () -> tenantService.createClient("Bar", partnerId).id());
        UUID otherClientId = asTenant(partnerId, () -> tenantService.createClient("Otro bar", partnerId).id());
        partnerAuth = bearerFor(partnerId);
        clientAuth = bearerFor(clientId);
        otherClientAuth = bearerFor(otherClientId);
    }

    @AfterEach
    void tearDown() {
        llm.reset();
    }

    @Test
    void profile_isLoadedByTheBusiness_readUpTheHierarchy_andHiddenAcrossIt() throws Exception {
        mockMvc.perform(put("/v1/tenants/" + clientId + "/business-profile").header(HttpHeaders.AUTHORIZATION, clientAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value(clientId.toString()))
                .andExpect(jsonPath("$.locale").value("es"));

        mockMvc.perform(get("/v1/tenants/" + clientId + "/business-profile").header(HttpHeaders.AUTHORIZATION, partnerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.time_zone").value("Europe/Madrid"))
                .andExpect(jsonPath("$.facts.horario").value("mar-dom 12:00-16:00"));
        mockMvc.perform(get("/v1/tenants/" + clientId + "/business-profile")
                        .header(HttpHeaders.AUTHORIZATION, otherClientAuth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("business_profile_not_found"));
        mockMvc.perform(put("/v1/tenants/" + clientId + "/business-profile")
                        .header(HttpHeaders.AUTHORIZATION, otherClientAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("tenant_not_found"));
        mockMvc.perform(put("/v1/tenants/" + partnerId + "/business-profile").header(HttpHeaders.AUTHORIZATION, partnerAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_tenant_tier"));
    }

    @Test
    void profile_withAnUnknownZone_andCatalog_withARepeatedRef_areBadRequests() throws Exception {
        mockMvc.perform(put("/v1/tenants/" + clientId + "/business-profile").header(HttpHeaders.AUTHORIZATION, clientAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Bar\", \"time_zone\": \"Mars/Olympus\", \"facts\": {}}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/v1/tenants/" + clientId + "/catalog").header(HttpHeaders.AUTHORIZATION, clientAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"ref\": \"a\", \"section\": \"S\", \"name\": \"A\"},"
                                + "{\"ref\": \"a\", \"section\": \"S\", \"name\": \"B\"}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/tenants/" + clientId + "/catalog").header(HttpHeaders.AUTHORIZATION, clientAuth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("catalog_not_found"));
    }

    @Test
    void agent_readsTheCatalogThroughItsTool_andRecordsWhatItCannotAnswer() throws Exception {
        mockMvc.perform(put("/v1/tenants/" + clientId + "/catalog").header(HttpHeaders.AUTHORIZATION, clientAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(CATALOG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].price").value(5.5))
                .andExpect(jsonPath("$.items[1].attributes").isEmpty());
        UUID agentId = createAgent(List.of("search_catalog", "record_unanswered_question"));
        llm.script(
                toolCall("search_catalog", Map.of("query", "tarta de queso")),
                toolCall("record_unanswered_question", Map.of("question", "¿Alérgenos de la tarta de queso?")),
                finalReply("De la tarta de queso no consta la información de alérgenos."));

        UUID conversationId = sendAndProcess(agentId, "¿Qué alérgenos tiene la tarta de queso?");

        String messages = mockMvc.perform(get("/v1/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, clientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].role").value("TOOL_RESULT"))
                .andReturn().getResponse().getContentAsString();
        String catalogOutput = objectMapper.readTree(messages).at("/data/2/tool_content/output").asText();
        assertThat(catalogOutput).contains("\"allergens\":\"NOT_DECLARED\"").contains("Tarta de queso")
                .doesNotContain("bravas");
        // The model was offered exactly the agent's grant.
        assertThat(llm.invocations().get(0).tools()).extracting(tool -> tool.name())
                .containsExactly("search_catalog", "record_unanswered_question");

        mockMvc.perform(get("/v1/tenants/" + clientId + "/unanswered-questions").header(HttpHeaders.AUTHORIZATION, partnerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].question").value("¿Alérgenos de la tarta de queso?"))
                .andExpect(jsonPath("$.data[0].agent_id").value(agentId.toString()))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
        mockMvc.perform(get("/v1/tenants/" + clientId + "/unanswered-questions")
                        .header(HttpHeaders.AUTHORIZATION, otherClientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        // C5: the lookups are in the audit chain, by hash.
        assertThat(new JdbcTemplate(adminDataSource).queryForObject("SELECT count(*) FROM audit_outbox WHERE "
                + "event_type = 'conduct.tool.called' AND tenant_id = ?", Long.class, clientId)).isEqualTo(2);
    }

    private UUID createAgent(List<String> tools) throws Exception {
        String body = mockMvc.perform(post("/v1/tenants/" + clientId + "/agents").header(HttpHeaders.AUTHORIZATION, clientAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Camarero",
                                "system_prompt", "Eres el camarero.", "model_provider", "anthropic",
                                "model_name", "claude-sonnet-4-7", "tools", tools))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    /** Posts a message, runs the worker and waits for the invocation to complete. */
    private UUID sendAndProcess(UUID agentId, String content) throws Exception {
        String body = mockMvc.perform(post("/v1/agents/" + agentId + "/messages").header(HttpHeaders.AUTHORIZATION, clientAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("external_identity_ref", "cliente-1",
                                "content", content))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String invocationId = objectMapper.readTree(body).get("invocation_id").asText();
        worker.pollAndProcess();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mockMvc.perform(get("/v1/invocations/" + invocationId).header(HttpHeaders.AUTHORIZATION, clientAuth))
                        .andExpect(jsonPath("$.status").value("COMPLETED")));
        return UUID.fromString(objectMapper.readTree(body).get("conversation_id").asText());
    }

    private String bearerFor(UUID tenantId) {
        return "Bearer " + asTenant(tenantId, () -> apiKeyService.createApiKey(tenantId, "it-key").plaintextKey());
    }

    private static <T> T asTenant(UUID tenantId, Supplier<T> action) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }
}
