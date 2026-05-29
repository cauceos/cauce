package dev.cauce.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.ApiKeyCreationResult;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import dev.cauce.tenancy.apikey.ApiKeyCache;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests for {@link ApiKeyAuthenticationFilter}, the {@link SecurityConfig}
 * filter chain, and the cache amortisation that backs the hot path. Runs against the
 * real datasource via Testcontainers and the real {@code BCryptPasswordEncoder} so
 * the hashing/verification round-trip is exercised, not stubbed.
 */
class ApiKeyAuthenticationFilterIT extends AbstractApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ApiKeyCache apiKeyCache;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Tenant clientA;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE api_keys, pending_invocations, messages, "
                + "conversations, agents, tenants CASCADE");
        TenantContext.clear();
        apiKeyCache.invalidateAll();

        Tenant operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        Tenant partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        clientA = tenantService.createClient("Client A", partner.id());
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        apiKeyCache.invalidateAll();
    }

    // === permitAll endpoints ===

    @Test
    void actuatorHealth_isReachableWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    // === 401 on bad credentials ===

    @Test
    void protectedEndpoint_withoutAuthorization_returns401() throws Exception {
        mockMvc.perform(get("/test/protected-resource"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withNonBearerAuthorization_returns401() throws Exception {
        mockMvc.perform(get("/test/protected-resource").header("Authorization", "Basic something"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withMalformedBearerKey_returns401() throws Exception {
        mockMvc.perform(get("/test/protected-resource").header("Authorization", "Bearer not-a-real-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withWellFormedButUnknownKey_returns401() throws Exception {
        String unknown = "ck_" + "z".repeat(32);

        mockMvc.perform(get("/test/protected-resource").header("Authorization", "Bearer " + unknown))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withRevokedKey_returns401() throws Exception {
        ApiKeyCreationResult created = createKeyAs(clientA.id(), "to-revoke");
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            apiKeyService.revokeApiKey(created.apiKey().id());
        } finally {
            TenantContext.clear();
        }

        mockMvc.perform(get("/test/protected-resource")
                        .header("Authorization", "Bearer " + created.plaintextKey()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withExpiredKey_returns401() throws Exception {
        ApiKeyCreationResult created = createKeyAs(clientA.id(), "to-expire");
        // Backdate both created_at and expires_at into the past, preserving the
        // CHECK constraint expires_at >= created_at. The key is now expired.
        jdbc.update("UPDATE api_keys SET created_at = ?, expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(120)),
                Timestamp.from(Instant.now().minusSeconds(60)),
                created.apiKey().id());

        mockMvc.perform(get("/test/protected-resource")
                        .header("Authorization", "Bearer " + created.plaintextKey()))
                .andExpect(status().isUnauthorized());
    }

    // === 200 on valid key ===

    @Test
    void protectedEndpoint_withValidKey_returns200AndSetsTenantContext() throws Exception {
        ApiKeyCreationResult created = createKeyAs(clientA.id(), "production");

        mockMvc.perform(get("/test/protected-resource")
                        .header("Authorization", "Bearer " + created.plaintextKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(clientA.id().toString()));
    }

    @Test
    void protectedEndpoint_afterRequest_tenantContextIsCleared() throws Exception {
        ApiKeyCreationResult created = createKeyAs(clientA.id(), "production");

        mockMvc.perform(get("/test/protected-resource")
                        .header("Authorization", "Bearer " + created.plaintextKey()))
                .andExpect(status().isOk());

        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    @Test
    void protectedEndpoint_validKey_updatesLastUsedAtOnFirstHit() throws Exception {
        ApiKeyCreationResult created = createKeyAs(clientA.id(), "production");
        // Pre-condition: last_used_at starts as NULL.
        assertThat(rawLastUsedAt(created.apiKey().id())).isNull();

        mockMvc.perform(get("/test/protected-resource")
                        .header("Authorization", "Bearer " + created.plaintextKey()))
                .andExpect(status().isOk());

        // Post-condition: last_used_at is populated.
        assertThat(rawLastUsedAt(created.apiKey().id())).isNotNull();
    }

    @Test
    void protectedEndpoint_validKey_secondHitIsCachedAndDoesNotUpdateLastUsedAt() throws Exception {
        ApiKeyCreationResult created = createKeyAs(clientA.id(), "production");

        mockMvc.perform(get("/test/protected-resource")
                        .header("Authorization", "Bearer " + created.plaintextKey()))
                .andExpect(status().isOk());
        Timestamp first = rawLastUsedAt(created.apiKey().id());
        assertThat(first).isNotNull();

        // Force a measurable time gap so any second update would change the stored value.
        Thread.sleep(20);
        mockMvc.perform(get("/test/protected-resource")
                        .header("Authorization", "Bearer " + created.plaintextKey()))
                .andExpect(status().isOk());
        Timestamp second = rawLastUsedAt(created.apiKey().id());

        assertThat(second).isEqualTo(first);
    }

    @Test
    void revoke_invalidatesCacheImmediately() throws Exception {
        ApiKeyCreationResult created = createKeyAs(clientA.id(), "production");
        // Warm the cache via a successful request.
        mockMvc.perform(get("/test/protected-resource")
                        .header("Authorization", "Bearer " + created.plaintextKey()))
                .andExpect(status().isOk());

        TenantContext.setCurrentTenantId(clientA.id());
        try {
            apiKeyService.revokeApiKey(created.apiKey().id());
        } finally {
            TenantContext.clear();
        }

        // Immediately after revocation the same key must be rejected, even though the
        // TTL has not elapsed: the service invalidated the cache entry.
        mockMvc.perform(get("/test/protected-resource")
                        .header("Authorization", "Bearer " + created.plaintextKey()))
                .andExpect(status().isUnauthorized());
    }

    private ApiKeyCreationResult createKeyAs(UUID context, String name) {
        TenantContext.setCurrentTenantId(context);
        try {
            return apiKeyService.createApiKey(context, name);
        } finally {
            TenantContext.clear();
        }
    }

    private Timestamp rawLastUsedAt(UUID apiKeyId) {
        return jdbc.queryForObject(
                "SELECT last_used_at FROM api_keys WHERE id = ?",
                Timestamp.class, apiKeyId);
    }
}
