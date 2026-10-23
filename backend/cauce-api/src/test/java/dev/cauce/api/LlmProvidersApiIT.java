package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.llm.spi.LlmProviderRegistry;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end test of {@code GET /v1/llm-providers}: each provider an agent may name and whether the
 * instance has it configured, read by any valid key — a client's too, since the list is instance
 * configuration and no tenant's data — and never a setting beyond that yes/no.
 */
class LlmProvidersApiIT extends AbstractApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private LlmProviderRegistry registry;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Environment environment;

    private String clientAuth;

    @BeforeEach
    void setUp() {
        truncateAll();
        TenantContext.clear();
        UUID operatorId = tenantService.bootstrapOperator("Operator").id();
        UUID partnerId = asTenant(operatorId, () -> tenantService.createPartner("Partner", operatorId).id());
        UUID clientId = asTenant(partnerId, () -> tenantService.createClient("Clinic", partnerId).id());
        clientAuth = "Bearer " + asTenant(clientId,
                () -> apiKeyService.createApiKey(clientId, "it-key").plaintextKey());
    }

    @Test
    void list_asClient_reportsEachProviderAsConfiguredExactlyWhenRegistered() throws Exception {
        String body = mockMvc.perform(get("/v1/llm-providers").header(HttpHeaders.AUTHORIZATION, clientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next_cursor").isEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).get("data");
        Set<String> configured = new HashSet<>();
        Set<String> listed = new HashSet<>();
        for (JsonNode provider : data) {
            listed.add(provider.get("id").asText());
            if (provider.get("configured").asBoolean()) {
                configured.add(provider.get("id").asText());
            }
        }
        assertThat(configured).isEqualTo(registry.availableProviders());
        assertThat(listed).containsAll(agentService.supportedProviders());
    }

    @Test
    void list_neverCarriesABaseUrlNorACredential() throws Exception {
        String body = mockMvc.perform(get("/v1/llm-providers").header(HttpHeaders.AUTHORIZATION, clientAuth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body.toLowerCase()).doesNotContain("http").doesNotContain("url")
                .doesNotContain("key").doesNotContain("secret").doesNotContain("token");
        String anthropicKey = environment.getProperty("ANTHROPIC_API_KEY");
        if (anthropicKey != null && !anthropicKey.isBlank()) {
            assertThat(body).doesNotContain(anthropicKey);
        }
    }

    @Test
    void list_withoutAuthorization_returns401() throws Exception {
        mockMvc.perform(get("/v1/llm-providers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
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
