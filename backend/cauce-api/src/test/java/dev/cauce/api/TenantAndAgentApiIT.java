package dev.cauce.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.agent.CreateAgentRequest;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.api.tenant.CreateClientRequest;
import dev.cauce.api.tenant.CreatePartnerRequest;
import dev.cauce.api.web.TenantContextFilter;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.TenantService;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end test of the tenant and agent REST surface against a real datasource, exercising
 * the {@code X-Tenant-Id} stopgap, hierarchical RLS over HTTP, and the operator→partner→
 * client→agent chain. Operator bootstrap is done via the service (never exposed over REST).
 */
class TenantAndAgentApiIT extends AbstractApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    private UUID operatorId;

    @BeforeEach
    void setUp() {
        new JdbcTemplate(dataSource).execute("TRUNCATE TABLE api_keys, pending_invocations, messages, "
                + "conversations, agents, tenants CASCADE");
        TenantContext.clear();
        operatorId = tenantService.bootstrapOperator("Operator").id();
    }

    @Test
    void createPartner_asOperator_returns201() throws Exception {
        mockMvc.perform(postAs(operatorId, "/v1/tenants/partner", new CreatePartnerRequest("Partner", operatorId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tier").value("PARTNER"))
                .andExpect(jsonPath("$.parent_tenant_id").value(operatorId.toString()));
    }

    @Test
    void createClient_asPartner_returns201() throws Exception {
        UUID partnerId = createPartner();

        mockMvc.perform(postAs(partnerId, "/v1/tenants/client", new CreateClientRequest("Client", partnerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tier").value("CLIENT"))
                .andExpect(jsonPath("$.parent_tenant_id").value(partnerId.toString()));
    }

    @Test
    void createAgent_asClient_returns201() throws Exception {
        UUID clientId = createClient(createPartner());

        mockMvc.perform(postAs(clientId, "/v1/tenants/" + clientId + "/agents",
                        new CreateAgentRequest("Bot", "You are helpful", "anthropic", "claude-opus-4-8", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.tenant_id").value(clientId.toString()));
    }

    @Test
    void getAndList_reflectTheCreatedHierarchy() throws Exception {
        UUID partnerId = createPartner();
        UUID clientId = createClient(partnerId);
        UUID agentId = createAgent(clientId);

        // Operator can read its partner and list its children.
        mockMvc.perform(get("/v1/tenants/" + partnerId).header(TenantContextFilter.TENANT_ID_HEADER, operatorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("PARTNER"));
        mockMvc.perform(get("/v1/tenants/" + operatorId + "/children").header(TenantContextFilter.TENANT_ID_HEADER, operatorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(partnerId.toString()));

        // Client can read its agent and list its agents.
        mockMvc.perform(get("/v1/agents/" + agentId).header(TenantContextFilter.TENANT_ID_HEADER, clientId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(agentId.toString()));
        mockMvc.perform(get("/v1/tenants/" + clientId + "/agents").header(TenantContextFilter.TENANT_ID_HEADER, clientId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void createClient_underOperatorParent_returns422() throws Exception {
        // Domain tier validation over HTTP: a CLIENT's parent must be a PARTNER, not an OPERATOR.
        mockMvc.perform(postAs(operatorId, "/v1/tenants/client", new CreateClientRequest("Client", operatorId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_tenant_tier"));
    }

    // NOTE on RLS: cross-tenant *read* isolation (one partner reading another partner's client)
    // is intentionally not asserted at this (API) layer. In dev/test the app connects with an
    // RLS-bypassing superuser role, so RLS does not filter here yet; it is enforced and verified
    // at the persistence layer via a dedicated restricted role (see cauce-tenancy ITs) and will
    // apply end-to-end once the cauce_app least-privilege role is wired (V1__create_tenants_table.sql).

    @Test
    void validationError_messageIsEnglish_regardlessOfServerLocale() throws Exception {
        // The validation message language is pinned to English (see ValidationConfig), so the
        // contract does not float with the host JVM/OS locale.
        mockMvc.perform(postAs(operatorId, "/v1/tenants/partner", new CreatePartnerRequest("  ", operatorId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));
    }

    @Test
    void missingTenantHeader_returns401() throws Exception {
        mockMvc.perform(post("/v1/tenants/partner").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePartnerRequest("Partner", operatorId))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("missing_tenant_context"));
    }

    @Test
    void malformedTenantHeader_returns400() throws Exception {
        mockMvc.perform(post("/v1/tenants/partner")
                        .header(TenantContextFilter.TENANT_ID_HEADER, "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePartnerRequest("Partner", operatorId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_tenant_id"));
    }

    // --- helpers ---

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postAs(
            UUID tenantId, String path, Object body) throws Exception {
        return post(path)
                .header(TenantContextFilter.TENANT_ID_HEADER, tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private UUID createPartner() throws Exception {
        String body = mockMvc.perform(postAs(operatorId, "/v1/tenants/partner", new CreatePartnerRequest("Partner", operatorId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private UUID createClient(UUID partnerId) throws Exception {
        String body = mockMvc.perform(postAs(partnerId, "/v1/tenants/client", new CreateClientRequest("Client", partnerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private UUID createAgent(UUID clientId) throws Exception {
        String body = mockMvc.perform(postAs(clientId, "/v1/tenants/" + clientId + "/agents",
                        new CreateAgentRequest("Bot", "You are helpful", "anthropic", "claude-opus-4-8", null, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }
}
