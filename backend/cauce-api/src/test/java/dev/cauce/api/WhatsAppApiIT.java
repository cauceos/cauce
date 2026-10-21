package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.agent.CreateAgentRequest;
import dev.cauce.api.channel.CreateChannelConfigRequest;
import dev.cauce.api.channel.RegisterWhatsAppAppRequest;
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
 * End-to-end tests of the WhatsApp channel's REST and webhook surface (ADR 0006) against a real
 * datasource, with API-key authentication and hierarchical RLS over HTTP.
 */
class WhatsAppApiIT extends AbstractApiIntegrationTest {

    static final String APP_SECRET = "meta-app-secret-for-tests";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ObjectMapper objectMapper;

    private String operatorAuth;
    private UUID operatorId;
    private UUID partnerId;
    private String partnerAuth;
    private UUID clientId;
    private String clientAuth;

    @BeforeEach
    void setUp() throws Exception {
        truncateAll();
        TenantContext.clear();
        operatorId = tenantService.bootstrapOperator("Operator").id();
        operatorAuth = bearerFor(operatorId);
        partnerId = idFrom(mockMvc.perform(postAs(operatorAuth, "/v1/tenants/partner",
                        new CreatePartnerRequest("Partner", operatorId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        partnerAuth = bearerFor(partnerId);
        clientId = idFrom(mockMvc.perform(postAs(partnerAuth, "/v1/tenants/client",
                        new CreateClientRequest("Client", partnerId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        clientAuth = bearerFor(clientId);
    }

    @Test
    void registerApp_returnsTheWebhookPathAndTheVerifyTokenOnce_neverTheSecret() throws Exception {
        String body = mockMvc.perform(postAs(partnerAuth, "/v1/tenants/" + partnerId + "/whatsapp-apps",
                        new RegisterWhatsAppAppRequest("1234567890", APP_SECRET)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant_id").value(partnerId.toString()))
                .andExpect(jsonPath("$.meta_app_id").value("1234567890"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.verify_token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(body);
        String appId = created.get("id").asText();
        assertThat(created.get("webhook_path").asText()).isEqualTo("/webhooks/whatsapp/" + appId);
        assertThat(body).doesNotContain(APP_SECRET);

        String read = mockMvc.perform(getAs(partnerAuth, "/v1/whatsapp-apps/" + appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verify_token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(read).doesNotContain(APP_SECRET);
        // A client below the partner does not see its partner's app.
        mockMvc.perform(getAs(clientAuth, "/v1/whatsapp-apps/" + appId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("whatsapp_app_not_found"));
    }

    @Test
    void registerApp_forATenantOutsideTheCallersScope_is404() throws Exception {
        mockMvc.perform(postAs(clientAuth, "/v1/tenants/" + partnerId + "/whatsapp-apps",
                        new RegisterWhatsAppAppRequest("1234567890", APP_SECRET)))
                .andExpect(status().isNotFound());
    }

    @Test
    void bindNumber_byThePartner_forItsClientsAgent_returnsTheBindingWithoutASecret() throws Exception {
        String appId = registerApp(partnerAuth, partnerId);
        UUID agentId = createAgent();

        String body = mockMvc.perform(postAs(partnerAuth, "/v1/agents/" + agentId + "/channels",
                        new CreateChannelConfigRequest("whatsapp", "EAAG-access-token", UUID.fromString(appId),
                                "1111111111")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel_type").value("whatsapp"))
                .andExpect(jsonPath("$.whatsapp_app_id").value(appId))
                .andExpect(jsonPath("$.phone_number_id").value("1111111111"))
                .andExpect(jsonPath("$.webhook_secret").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("EAAG-access-token");

        // One number is one door.
        mockMvc.perform(postAs(partnerAuth, "/v1/agents/" + agentId + "/channels",
                        new CreateChannelConfigRequest("whatsapp", "t", UUID.fromString(appId), "1111111111")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("phone_number_already_bound"));
        // A WhatsApp binding names its app and number.
        mockMvc.perform(postAs(partnerAuth, "/v1/agents/" + agentId + "/channels",
                        new CreateChannelConfigRequest("whatsapp", "t", null, "2222222222")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_channel_config_request"));
    }

    // --- helpers ---

    UUID createAgent() throws Exception {
        return idFrom(mockMvc.perform(postAs(clientAuth, "/v1/tenants/" + clientId + "/agents",
                        new CreateAgentRequest("Bot", "You are helpful", "anthropic", "claude-sonnet-4-7",
                                null, null, null, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    String registerApp(String auth, UUID tenantId) throws Exception {
        return idFrom(mockMvc.perform(postAs(auth, "/v1/tenants/" + tenantId + "/whatsapp-apps",
                        new RegisterWhatsAppAppRequest("1234567890", APP_SECRET)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).toString();
    }

    private UUID idFrom(String body) throws Exception {
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
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
}
