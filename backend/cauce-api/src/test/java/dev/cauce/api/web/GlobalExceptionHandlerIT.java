package dev.cauce.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests for {@link GlobalExceptionHandler}: every representative exception maps
 * to the right HTTP status and the uniform {@link ErrorResponse} body, server errors do not
 * leak internals, and authentication failures share the same error shape.
 *
 * <p>The {@code /test/throw/**} endpoints are protected (everything but the actuator is), so
 * each request carries a freshly minted, valid API key. The controller then throws, and the
 * advice — running after authentication but before the request unwinds — produces the body.
 */
class GlobalExceptionHandlerIT extends AbstractApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private DataSource dataSource;

    private String bearer;

    @BeforeEach
    void setUp() {
        new JdbcTemplate(dataSource).execute("TRUNCATE TABLE api_keys, pending_invocations, messages, "
                + "conversations, agents, tenants CASCADE");
        TenantContext.clear();

        Tenant operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        try {
            bearer = "Bearer " + apiKeyService.createApiKey(operator.id(), "test").plaintextKey();
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void notFoundException_maps404WithCodeAndRequestId() throws Exception {
        mockMvc.perform(get("/test/throw/not-found").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("tenant_not_found"))
                .andExpect(jsonPath("$.message").value("tenant 123 not found"))
                .andExpect(jsonPath("$.request_id").isNotEmpty())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER));
    }

    @Test
    void invalidTransitionException_maps400() throws Exception {
        mockMvc.perform(get("/test/throw/bad-request").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_conversation_transition"));
    }

    @Test
    void missingTenantContextException_maps401WithDomainCode() throws Exception {
        // Distinct from an authentication failure: this is an authenticated request whose
        // handler found no tenant context, so the code is the domain one, not "unauthorized".
        mockMvc.perform(get("/test/throw/missing-context").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("missing_tenant_context"));
    }

    @Test
    void alreadyRevokedException_maps409() throws Exception {
        mockMvc.perform(get("/test/throw/conflict").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("api_key_already_revoked"));
    }

    @Test
    void unknownModelException_maps422() throws Exception {
        mockMvc.perform(get("/test/throw/unprocessable").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("unknown_model"));
    }

    @Test
    void llmAuthenticationException_maps502WithGenericMessage() throws Exception {
        mockMvc.perform(get("/test/throw/bad-gateway").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("llm_authentication_error"))
                // Generic body: the provider/model context in the exception must not leak.
                .andExpect(jsonPath("$.message").value("The upstream LLM provider returned an error"));
    }

    @Test
    void llmRateLimitException_maps503WithRetryAfterHeader() throws Exception {
        mockMvc.perform(get("/test/throw/rate-limit").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("llm_rate_limit"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"));
    }

    @Test
    void llmTimeoutException_maps504() throws Exception {
        mockMvc.perform(get("/test/throw/timeout").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error").value("llm_timeout"));
    }

    @Test
    void unexpectedException_maps500WithoutLeakingInternals() throws Exception {
        String body = mockMvc.perform(get("/test/throw/unexpected").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("internal_error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.request_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(TestExceptionEndpoints.INTERNAL_DETAIL);
    }

    @Test
    void authFailure_withInvalidKey_returnsUnifiedErrorResponse() throws Exception {
        // The auth filter writes the 401 directly (outside MVC); it must still be the new
        // ErrorResponse shape with a generic message and a request_id, not the old body.
        mockMvc.perform(get("/test/throw/not-found").header(HttpHeaders.AUTHORIZATION, "Bearer ck_" + "z".repeat(32)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.message").value("Valid API key required"))
                .andExpect(jsonPath("$.request_id").isNotEmpty())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER));
    }

    @Test
    void authFailure_withoutAuthorization_returnsUnifiedErrorResponse() throws Exception {
        // The authentication entry point path (no credential at all) yields the same shape.
        mockMvc.perform(get("/test/throw/not-found"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.message").value("Valid API key required"))
                .andExpect(jsonPath("$.request_id").isNotEmpty())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER));
    }
}
