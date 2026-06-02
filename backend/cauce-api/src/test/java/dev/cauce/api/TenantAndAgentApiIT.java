package dev.cauce.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.agent.CreateAgentRequest;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.api.tenant.CreateClientRequest;
import dev.cauce.api.tenant.CreatePartnerRequest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end test of the tenant and agent REST surface against a real datasource, exercising
 * API-key authentication ({@code Authorization: Bearer}), hierarchical RLS over HTTP, and the
 * operator→partner→client→agent chain. The tenant context is derived from the validated key,
 * never from a client header. The operator tenant is created via the bootstrap service; each
 * actor's key is minted via the admin path ({@link ApiKeyService}) in setup, since key issuance
 * is not exposed over REST.
 */
class TenantAndAgentApiIT extends AbstractApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID operatorId;
    private String operatorAuth;

    @BeforeEach
    void setUp() {
        truncateAll();
        TenantContext.clear();
        operatorId = tenantService.bootstrapOperator("Operator").id();
        operatorAuth = bearerFor(operatorId);
    }

    @Test
    void createPartner_asOperator_returns201() throws Exception {
        mockMvc.perform(postAs(operatorAuth, "/v1/tenants/partner", new CreatePartnerRequest("Partner", operatorId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tier").value("PARTNER"))
                .andExpect(jsonPath("$.parent_tenant_id").value(operatorId.toString()));
    }

    @Test
    void createClient_asPartner_returns201() throws Exception {
        UUID partnerId = createPartner();
        String partnerAuth = bearerFor(partnerId);

        mockMvc.perform(postAs(partnerAuth, "/v1/tenants/client", new CreateClientRequest("Client", partnerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tier").value("CLIENT"))
                .andExpect(jsonPath("$.parent_tenant_id").value(partnerId.toString()));
    }

    @Test
    void createAgent_asClient_returns201() throws Exception {
        UUID partnerId = createPartner();
        UUID clientId = createClient(bearerFor(partnerId), partnerId);
        String clientAuth = bearerFor(clientId);

        mockMvc.perform(postAs(clientAuth, "/v1/tenants/" + clientId + "/agents",
                        new CreateAgentRequest("Bot", "You are helpful", "anthropic", "claude-opus-4-8", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.tenant_id").value(clientId.toString()));
    }

    @Test
    void getAndList_reflectTheCreatedHierarchy() throws Exception {
        UUID partnerId = createPartner();
        String partnerAuth = bearerFor(partnerId);
        UUID clientId = createClient(partnerAuth, partnerId);
        String clientAuth = bearerFor(clientId);
        UUID agentId = createAgent(clientAuth, clientId);

        // Operator can read its partner and list its children.
        mockMvc.perform(getAs(operatorAuth, "/v1/tenants/" + partnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("PARTNER"));
        mockMvc.perform(getAs(operatorAuth, "/v1/tenants/" + operatorId + "/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(partnerId.toString()));

        // Client can read its agent and list its agents.
        mockMvc.perform(getAs(clientAuth, "/v1/agents/" + agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(agentId.toString()));
        mockMvc.perform(getAs(clientAuth, "/v1/tenants/" + clientId + "/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void createClient_underOperatorParent_returns422() throws Exception {
        // Domain tier validation over HTTP: a CLIENT's parent must be a PARTNER, not an OPERATOR.
        mockMvc.perform(postAs(operatorAuth, "/v1/tenants/client", new CreateClientRequest("Client", operatorId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_tenant_tier"));
    }

    @Test
    void getAgent_fromUnrelatedTenant_returns404_andHeaderCannotImpersonate() throws Exception {
        // partnerA -> clientA -> agentA; partnerB is a sibling partner with no path to agentA.
        UUID partnerA = createPartner();
        String partnerAAuth = bearerFor(partnerA);
        UUID partnerB = createPartner();
        String partnerBAuth = bearerFor(partnerB);
        UUID clientA = createClient(partnerAAuth, partnerA);
        String clientAAuth = bearerFor(clientA);
        UUID agentA = createAgent(clientAAuth, clientA);

        // An unrelated tenant's key cannot read agentA: the query is scoped to the AUTHENTICATED
        // tenant, so the agent is not found -> 404 (not-found and not-visible are indistinguishable).
        mockMvc.perform(getAs(partnerBAuth, "/v1/agents/" + agentA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("agent_not_found"));

        // Impersonation is closed: spoofing the old X-Tenant-Id header with the OWNING tenant
        // (clientA) — which would have granted access under the stopgap — still yields 404, because
        // the validated key (partnerB) decides the tenant and the header is ignored.
        mockMvc.perform(get("/v1/agents/" + agentA)
                        .header(HttpHeaders.AUTHORIZATION, partnerBAuth)
                        .header("X-Tenant-Id", clientA.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("agent_not_found"));

        // The owning tenant's own key reads its agent -> 200 (getAgent is exact-owner; ancestors
        // see a client's agents via the list endpoint, not GET-by-id).
        mockMvc.perform(getAs(clientAAuth, "/v1/agents/" + agentA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(agentA.toString()));
    }

    @Test
    void validationError_messageIsEnglish_regardlessOfServerLocale() throws Exception {
        // The validation message language is pinned to English (see ValidationConfig), so the
        // contract does not float with the host JVM/OS locale.
        mockMvc.perform(postAs(operatorAuth, "/v1/tenants/partner", new CreatePartnerRequest("  ", operatorId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));
    }

    @Test
    void noAuthorization_returns401WithBearerChallenge() throws Exception {
        mockMvc.perform(post("/v1/tenants/partner").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePartnerRequest("Partner", operatorId))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void garbageBearerKey_returns401() throws Exception {
        mockMvc.perform(getAs("Bearer ck_" + "z".repeat(32), "/v1/tenants/" + operatorId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    // --- helpers ---

    /** Mints a fresh API key for {@code tenantId} via the admin path and returns the Bearer value. */
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
        String body = mockMvc.perform(postAs(operatorAuth, "/v1/tenants/partner", new CreatePartnerRequest("Partner", operatorId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private UUID createClient(String partnerAuth, UUID partnerId) throws Exception {
        String body = mockMvc.perform(postAs(partnerAuth, "/v1/tenants/client", new CreateClientRequest("Client", partnerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private UUID createAgent(String clientAuth, UUID clientId) throws Exception {
        String body = mockMvc.perform(postAs(clientAuth, "/v1/tenants/" + clientId + "/agents",
                        new CreateAgentRequest("Bot", "You are helpful", "anthropic", "claude-opus-4-8", null, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }
}
