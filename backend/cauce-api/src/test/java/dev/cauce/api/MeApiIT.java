package dev.cauce.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * End-to-end test of {@code GET /v1/me} — key introspection. The identity comes entirely from
 * the authenticated principal (tenant + key) and the caller's own always-visible tenant, so the
 * response mirrors exactly what the key already established: no visibility is widened, no params
 * are accepted.
 */
class MeApiIT extends AbstractApiIntegrationTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

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
    void me_asOperator_returnsItsOwnIdentity() throws Exception {
        mockMvc.perform(get("/v1/me").header(HttpHeaders.AUTHORIZATION, operatorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value(operatorId.toString()))
                .andExpect(jsonPath("$.tenant_name").value("Operator"))
                .andExpect(jsonPath("$.tier").value("OPERATOR"))
                .andExpect(jsonPath("$.key_id").exists());
    }

    @Test
    void me_asClient_returnsThatClientsIdentity() throws Exception {
        UUID partnerId = asTenant(operatorId, () -> tenantService.createPartner("Partner", operatorId).id());
        UUID clientId = asTenant(partnerId, () -> tenantService.createClient("Clinic", partnerId).id());
        String clientAuth = bearerFor(clientId);

        mockMvc.perform(get("/v1/me").header(HttpHeaders.AUTHORIZATION, clientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value(clientId.toString()))
                .andExpect(jsonPath("$.tenant_name").value("Clinic"))
                .andExpect(jsonPath("$.tier").value("CLIENT"))
                .andExpect(jsonPath("$.key_id").exists());
    }

    @Test
    void me_withoutAuthorization_returns401() throws Exception {
        mockMvc.perform(get("/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    // --- helpers ---

    /** Mints a fresh API key for {@code tenantId} via the admin path and returns the Bearer value. */
    private String bearerFor(UUID tenantId) {
        return "Bearer " + asTenant(tenantId, () -> apiKeyService.createApiKey(tenantId, "it-key").plaintextKey());
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
