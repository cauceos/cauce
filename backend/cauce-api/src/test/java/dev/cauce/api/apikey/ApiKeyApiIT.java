package dev.cauce.api.apikey;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * End-to-end tests for the API-key lifecycle endpoints under real authentication (the app connects
 * as cauce_app; requests carry a Bearer key): hierarchical issuance, the visibility-based authority
 * model, metadata-only listing, and soft revocation. The operator's first key is minted via the
 * service (no key exists yet to call the API with); every other key is minted through the HTTP
 * endpoint under test.
 */
class ApiKeyApiIT extends AbstractApiIntegrationTest {

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
        operatorAuth = "Bearer " + mintViaService(operatorId);
    }

    @Test
    void operatorMintsPartnerKey_andPartnerCanActWithIt() throws Exception {
        UUID partnerId = createPartner();

        // Hierarchical issuance: the operator mints a key for its partner -> 201 + plaintext once.
        String body = mockMvc.perform(postAs(operatorAuth, "/v1/tenants/" + partnerId + "/api-keys",
                        new CreateApiKeyRequest("partner-key")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.api_key").isNotEmpty())
                .andExpect(jsonPath("$.tenant_id").value(partnerId.toString()))
                .andExpect(jsonPath("$.label").value("partner-key"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        String partnerKey = objectMapper.readTree(body).get("api_key").asText();

        // The minted key authenticates and acts as the partner.
        mockMvc.perform(postAs("Bearer " + partnerKey, "/v1/tenants/client",
                        new CreateClientRequest("Client", partnerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tier").value("CLIENT"));
    }

    @Test
    void mintingForNonVisibleTenant_returns404_butForSelf_201() throws Exception {
        UUID partnerA = createPartner();
        UUID partnerB = createPartner();
        String partnerAAuth = "Bearer " + mintViaService(partnerA);
        String partnerBAuth = "Bearer " + mintViaService(partnerB);
        UUID clientA = createClientAs(partnerAAuth, partnerA);

        // partnerB cannot mint a key for clientA — it is outside partnerB's subtree -> 404.
        mockMvc.perform(postAs(partnerBAuth, "/v1/tenants/" + clientA + "/api-keys",
                        new CreateApiKeyRequest("intrusion")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("tenant_not_found"));

        // ... but it can mint a key for itself.
        mockMvc.perform(postAs(partnerBAuth, "/v1/tenants/" + partnerB + "/api-keys",
                        new CreateApiKeyRequest("own")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant_id").value(partnerB.toString()));
    }

    @Test
    void list_returnsMetadataOnly_andAncestorSeesDescendantKeys() throws Exception {
        UUID partnerId = createPartner();
        String partnerAuth = "Bearer " + mintViaService(partnerId); // key #1 (the partner's own)
        mintViaApi(partnerAuth, partnerId, "k1");                    // key #2

        // The operator (ancestor) lists the partner's keys: metadata only — never the hash or plaintext.
        mockMvc.perform(getAs(operatorAuth, "/v1/tenants/" + partnerId + "/api-keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].key_prefix").isNotEmpty())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].key_hash").doesNotExist())
                .andExpect(jsonPath("$[0].api_key").doesNotExist())
                .andExpect(jsonPath("$[1].key_hash").doesNotExist());
    }

    @Test
    void revoke_stopsAuthentication_andAncestorCanRevokeDescendantKey() throws Exception {
        UUID partnerId = createPartner();

        // Operator mints a key for the partner; capture its plaintext and id.
        String body = mockMvc.perform(postAs(operatorAuth, "/v1/tenants/" + partnerId + "/api-keys",
                        new CreateApiKeyRequest("to-revoke")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String key = objectMapper.readTree(body).get("api_key").asText();
        UUID keyId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        // It authenticates before revocation.
        mockMvc.perform(getAs("Bearer " + key, "/v1/tenants/" + partnerId)).andExpect(status().isOk());

        // The operator (ancestor) revokes the partner's key -> 204.
        mockMvc.perform(delete("/v1/api-keys/" + keyId).header(HttpHeaders.AUTHORIZATION, operatorAuth))
                .andExpect(status().isNoContent());

        // The revoked key no longer authenticates -> 401.
        mockMvc.perform(getAs("Bearer " + key, "/v1/tenants/" + partnerId))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    /** Mints a key for {@code tenantId} directly via the service (used to bootstrap an actor's key). */
    private String mintViaService(UUID tenantId) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return apiKeyService.createApiKey(tenantId, "svc").plaintextKey();
        } finally {
            TenantContext.clear();
        }
    }

    private String mintViaApi(String auth, UUID tenantId, String label) throws Exception {
        String body = mockMvc.perform(postAs(auth, "/v1/tenants/" + tenantId + "/api-keys",
                        new CreateApiKeyRequest(label)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("api_key").asText();
    }

    private UUID createPartner() throws Exception {
        String body = mockMvc.perform(postAs(operatorAuth, "/v1/tenants/partner",
                        new CreatePartnerRequest("Partner", operatorId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private UUID createClientAs(String auth, UUID partnerId) throws Exception {
        String body = mockMvc.perform(postAs(auth, "/v1/tenants/client",
                        new CreateClientRequest("Client", partnerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
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
