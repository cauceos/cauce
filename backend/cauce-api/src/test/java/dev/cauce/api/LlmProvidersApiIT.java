package dev.cauce.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.llm.spi.LlmProviderRegistry;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end test of {@code GET /v1/llm-providers}: the instance's configured providers, read by
 * any valid key — a client's too, since the list is instance configuration and no tenant's data.
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
    void list_asClient_returnsTheRegisteredProviders() throws Exception {
        mockMvc.perform(get("/v1/llm-providers").header(HttpHeaders.AUTHORIZATION, clientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(registry.availableProviders().size()))
                .andExpect(jsonPath("$.next_cursor").isEmpty());
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
